#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <cstring>

#include "llama.h"

#define TAG "LlamaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_ctx = 2048;
};

std::once_flag g_backend_once;

std::string jstring_to_std(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring std_to_jstring(JNIEnv * env, const std::string & s) {
    return env->NewStringUTF(s.c_str());
}

// 将 UTF-8 安全地交给 Kotlin（避免 NewStringUTF 遇到 4 字节字符出问题）
jstring utf8_to_jstring(JNIEnv * env, const std::string & s) {
    jclass cls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    jbyteArray bytes = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    jstring charset = env->NewStringUTF("UTF-8");
    jstring out = (jstring) env->NewObject(cls, ctor, bytes, charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_io_dsh_localkb_LocalLlm_nativeVersion(JNIEnv * env, jclass) {
    return env->NewStringUTF("llama.cpp bridge v1");
}

JNIEXPORT jlong JNICALL
Java_io_dsh_localkb_LocalLlm_nativeLoad(JNIEnv * env, jclass, jstring jpath, jint nThreads, jint nCtx) {
    std::call_once(g_backend_once, []() { llama_backend_init(); });

    const std::string path = jstring_to_std(env, jpath);
    auto * s = new Session();
    s->n_ctx = nCtx > 0 ? nCtx : 2048;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    s->model = llama_model_load_from_file(path.c_str(), mparams);
    if (s->model == nullptr) {
        LOGE("model load failed: %s", path.c_str());
        delete s;
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) s->n_ctx;
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = nThreads > 0 ? nThreads : 4;
    cparams.n_threads_batch = nThreads > 0 ? nThreads : 4;
    s->ctx = llama_init_from_model(s->model, cparams);
    if (s->ctx == nullptr) {
        LOGE("context init failed");
        llama_model_free(s->model);
        delete s;
        return 0;
    }
    s->vocab = llama_model_get_vocab(s->model);
    LOGI("model loaded: %s (n_ctx=%d)", path.c_str(), s->n_ctx);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_io_dsh_localkb_LocalLlm_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return;
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

/**
 * 生成文本。sink 为 Kotlin 回调（可为 null），每产出一个片段调用一次 sink.onToken(String)。
 * 返回完整文本。
 */
JNIEXPORT jstring JNICALL
Java_io_dsh_localkb_LocalLlm_nativeGenerate(
        JNIEnv * env, jclass, jlong handle, jstring jprompt,
        jint maxTokens, jfloat temp, jfloat topP, jobject sink) {

    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr || s->ctx == nullptr) return env->NewStringUTF("");

    const std::string prompt = jstring_to_std(env, jprompt);

    // 分词
    std::vector<llama_token> tokens(prompt.size() + 16);
    int n = llama_tokenize(s->vocab, prompt.c_str(), (int32_t) prompt.size(),
                           tokens.data(), (int32_t) tokens.size(), true, true);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(s->vocab, prompt.c_str(), (int32_t) prompt.size(),
                           tokens.data(), (int32_t) tokens.size(), true, true);
    }
    if (n <= 0) {
        LOGE("tokenize failed");
        return env->NewStringUTF("");
    }
    tokens.resize(n);

    // 截断，给生成留空间
    const int budget = s->n_ctx - (maxTokens > 0 ? maxTokens : 256) - 8;
    if (budget > 0 && n > budget) {
        tokens.erase(tokens.begin(), tokens.begin() + (n - budget));
        n = (int) tokens.size();
    }

    // 清空 KV，重新开始
    llama_memory_clear(llama_get_memory(s->ctx), true);

    // 关键：prompt 可能几千 token，必须按 n_batch 分批 decode，
    // 一次性提交超过 n_batch 会在 llama_context::decode 里 ggml_abort（闪退）
    const int n_batch = 512;
    for (int off = 0; off < n; off += n_batch) {
        const int cnt = (n - off < n_batch) ? (n - off) : n_batch;
        llama_batch batch = llama_batch_get_one(tokens.data() + off, cnt);
        if (llama_decode(s->ctx, batch) != 0) {
            LOGE("decode prompt failed at off=%d cnt=%d total=%d", off, cnt, n);
            return env->NewStringUTF("");
        }
    }

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP > 0 ? topP : 0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp > 0 ? temp : 0.8f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(1234));

    // Kotlin 回调
    jclass sinkCls = nullptr;
    jmethodID onToken = nullptr;
    if (sink != nullptr) {
        sinkCls = env->GetObjectClass(sink);
        onToken = env->GetMethodID(sinkCls, "onToken", "(Ljava/lang/String;)Z");
    }

    std::string out;
    const int limit = maxTokens > 0 ? maxTokens : 256;
    for (int i = 0; i < limit; ++i) {
        llama_token id = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, id)) break;

        char buf[256];
        int len = llama_token_to_piece(s->vocab, id, buf, sizeof(buf), 0, true);
        if (len > 0) {
            std::string piece(buf, len);
            // 有些 GGUF（如 Qwen）没有把 <|im_end|> 标成 EOG，这里手动截断
            bool stop = false;
            for (const char * marker : {"<|im_end|>", "<|endoftext|>", "</s>"}) {
                auto pos = piece.find(marker);
                if (pos != std::string::npos) {
                    piece = piece.substr(0, pos);
                    stop = true;
                    break;
                }
            }
            if (!piece.empty()) {
                out += piece;
                if (onToken != nullptr) {
                    jstring js = utf8_to_jstring(env, piece);
                    jboolean cont = env->CallBooleanMethod(sink, onToken, js);
                    env->DeleteLocalRef(js);
                    if (cont == JNI_FALSE) break;   // Kotlin 侧要求停止
                }
            }
            if (stop) break;
            // 结束标记可能被拆成多个 token，检查累计输出的结尾
            if (out.size() >= 10 && out.compare(out.size() - 10, 10, "<|im_end|>") == 0) break;
            if (out.size() >= 13 && out.compare(out.size() - 13, 13, "<|endoftext|>") == 0) break;
            if (out.size() >= 4 && out.compare(out.size() - 4, 4, "</s>") == 0) break;
        }

        llama_batch one = llama_batch_get_one(&id, 1);
        if (llama_decode(s->ctx, one) != 0) {
            LOGE("decode token failed at %d", i);
            break;
        }
    }

    llama_sampler_free(smpl);
    for (const char * marker : {"<|im_end|>", "<|endoftext|>", "</s>"}) {
        auto pos = out.find(marker);
        if (pos != std::string::npos) out = out.substr(0, pos);
    }
    return utf8_to_jstring(env, out);
}

} // extern "C"
