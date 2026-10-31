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
    std::string chat_template;
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

std::vector<std::string> read_string_array(JNIEnv * env, jobjectArray arr) {
    std::vector<std::string> out;
    if (arr == nullptr) return out;
    const jsize n = env->GetArrayLength(arr);
    out.reserve((size_t) n);
    for (jsize i = 0; i < n; ++i) {
        auto s = (jstring) env->GetObjectArrayElement(arr, i);
        out.push_back(jstring_to_std(env, s));
        env->DeleteLocalRef(s);
    }
    return out;
}

// 累计输出里是否以某个标记结尾（标记可能被拆成多个 token）
bool ends_with_any(const std::string & text, const std::vector<std::string> & markers) {
    for (const auto & m : markers) {
        if (!m.empty() && text.size() >= m.size() &&
            text.compare(text.size() - m.size(), m.size(), m) == 0) {
            return true;
        }
    }
    return false;
}

const std::vector<std::string> & default_stops() {
    static const std::vector<std::string> v = {
            "<|im_end|>", "<|im_start|>", "<|endoftext|>", "<|eot_id|>",
            "<|start_header_id|>", "<|end_header_id|>", "<|end|>",
            "<start_of_turn>", "<end_of_turn>", "</s>",
            "[gMASK]", "<sop>", "<|user|>", "<|assistant|>", "<|system|>",
    };
    return v;
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_io_dsh_localkb_LocalLlm_nativeVersion(JNIEnv * env, jclass) {
    return env->NewStringUTF("llama.cpp bridge v2");
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
    const char * tmpl = llama_model_chat_template(s->model, nullptr);
    if (tmpl != nullptr) s->chat_template = tmpl;
    LOGI("model loaded: %s (n_ctx=%d, template=%s)", path.c_str(), s->n_ctx,
         s->chat_template.empty() ? "none" : "builtin");
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

/** 返回模型自带的对话模板（GGUF tokenizer.chat_template），没有则返回空串。 */
JNIEXPORT jstring JNICALL
Java_io_dsh_localkb_LocalLlm_nativeChatTemplate(JNIEnv * env, jclass, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return env->NewStringUTF("");
    return utf8_to_jstring(env, s->chat_template);
}

/** 计算一段文本的 token 数；失败返回 -1。 */
JNIEXPORT jint JNICALL
Java_io_dsh_localkb_LocalLlm_nativeTokenCount(JNIEnv * env, jclass, jlong handle, jstring jtext) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr || s->vocab == nullptr) return -1;
    const std::string text = jstring_to_std(env, jtext);
    if (text.empty()) return 0;
    std::vector<llama_token> tokens(text.size() + 16);
    int n = llama_tokenize(s->vocab, text.c_str(), (int32_t) text.size(),
                           tokens.data(), (int32_t) tokens.size(), false, true);
    if (n < 0) return -n;   // llama 返回所需容量
    return n;
}

/**
 * 用模型自带模板渲染对话。roles/contents 等长，按顺序追加。
 * 成功返回完整提示词（已包含 assistant 起手标记）；模型无模板或模板不受支持返回空串。
 */
JNIEXPORT jstring JNICALL
Java_io_dsh_localkb_LocalLlm_nativeApplyTemplate(
        JNIEnv * env, jclass, jlong handle, jobjectArray jroles, jobjectArray jcontents) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr || s->model == nullptr) return env->NewStringUTF("");

    const auto roles = read_string_array(env, jroles);
    const auto contents = read_string_array(env, jcontents);
    if (roles.empty() || roles.size() != contents.size()) return env->NewStringUTF("");

    std::vector<llama_chat_message> msgs;
    msgs.reserve(roles.size());
    for (size_t i = 0; i < roles.size(); ++i) {
        msgs.push_back({roles[i].c_str(), contents[i].c_str()});
    }

    const char * tmpl = s->chat_template.empty() ? nullptr : s->chat_template.c_str();
    std::vector<char> buf(8192);
    int32_t need = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                             buf.data(), (int32_t) buf.size());
    if (need < 0) {
        LOGI("chat template not supported by llama_chat_apply_template");
        return env->NewStringUTF("");
    }
    if (need > (int32_t) buf.size()) {
        buf.resize((size_t) need + 1);
        need = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true,
                                         buf.data(), (int32_t) buf.size());
        if (need < 0) return env->NewStringUTF("");
    }
    return utf8_to_jstring(env, std::string(buf.data(), (size_t) need));
}

/**
 * 生成文本。sink 为 Kotlin 回调（可为 null），每产出一个片段调用一次 sink.onToken(String)。
 * stops 为额外停止标记（模型模板相关），命中即截断。
 * 返回完整文本；提示词超长时返回空串（Kotlin 侧会缩减历史后重试）。
 */
JNIEXPORT jstring JNICALL
Java_io_dsh_localkb_LocalLlm_nativeGenerate(
        JNIEnv * env, jclass, jlong handle, jstring jprompt,
        jint maxTokens, jfloat temp, jfloat topP, jobject sink, jobjectArray jstops) {

    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr || s->ctx == nullptr) return env->NewStringUTF("");

    const std::string prompt = jstring_to_std(env, jprompt);

    // 合并停止标记：调用方传入 + 内置兜底
    std::vector<std::string> stops = read_string_array(env, jstops);
    for (const auto & m : default_stops()) {
        bool dup = false;
        for (const auto & x : stops) if (x == m) { dup = true; break; }
        if (!dup) stops.push_back(m);
    }

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

    // 不再从头部硬截断（会切坏对话模板，模型会把模板标记当正文续写）。
    // 超长直接返回空串，由 Kotlin 缩减历史后重试。
    const int limit = maxTokens > 0 ? maxTokens : 256;
    const int budget = s->n_ctx - limit - 8;
    if (budget > 0 && n > budget) {
        LOGE("prompt too long: %d tokens > budget %d (n_ctx=%d), ask Kotlin to shrink",
             n, budget, s->n_ctx);
        return env->NewStringUTF("");
    }

    // 清空 KV，重新开始
    llama_memory_clear(llama_get_memory(s->ctx), true);

    // prompt 可能几千 token，必须按 n_batch 分批 decode，
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
    bool stopped = false;
    for (int i = 0; i < limit && !stopped; ++i) {
        llama_token id = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, id)) break;

        char buf[256];
        int len = llama_token_to_piece(s->vocab, id, buf, sizeof(buf), 0, true);
        if (len > 0) {
            std::string piece(buf, len);

            // 片段内命中停止标记 → 截断
            for (const auto & m : stops) {
                if (m.empty()) continue;
                auto pos = piece.find(m);
                if (pos != std::string::npos) {
                    piece = piece.substr(0, pos);
                    stopped = true;
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
            if (stopped) break;
            // 标记可能被拆成多个 token：检查累计输出结尾
            if (ends_with_any(out, stops)) {
                // 去掉已写出的标记尾部（一般上一步已在片段内截断，这里兜底）
                for (const auto & m : stops) {
                    if (!m.empty() && out.size() >= m.size() &&
                        out.compare(out.size() - m.size(), m.size(), m) == 0) {
                        out.erase(out.size() - m.size());
                        break;
                    }
                }
                break;
            }
        }

        llama_batch one = llama_batch_get_one(&id, 1);
        if (llama_decode(s->ctx, one) != 0) {
            LOGE("decode token failed at %d", i);
            break;
        }
    }

    llama_sampler_free(smpl);

    // 兜底：从最早出现的标记处截断
    size_t cut = std::string::npos;
    for (const auto & m : stops) {
        if (m.empty()) continue;
        auto pos = out.find(m);
        if (pos != std::string::npos && (cut == std::string::npos || pos < cut)) cut = pos;
    }
    if (cut != std::string::npos) out = out.substr(0, cut);

    return utf8_to_jstring(env, out);
}

} // extern "C"
