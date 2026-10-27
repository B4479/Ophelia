#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "VisionBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct vision_ctx {
    llama_model * model = nullptr;
    llama_context * lctx = nullptr;
    mtmd_context * mctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_threads = 4;
};

static void log_cb(enum ggml_log_level level, const char * text, void * /*user*/) {
    if (text == nullptr) return;
    int prio = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_DEBUG) prio = ANDROID_LOG_DEBUG;
    __android_log_print(prio, "mtmd-native", "%s", text);
}

static std::string jstr_to_std(JNIEnv * env, jstring s) {
    if (s == nullptr) return "";
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_dsh_localkb_LocalVision_nativeLoad(
        JNIEnv * env, jobject /*thiz*/,
        jstring modelPath, jstring mmprojPath, jint nThreads) {
    const std::string model_path = jstr_to_std(env, modelPath);
    const std::string mmproj_path = jstr_to_std(env, mmprojPath);

    llama_backend_init();
    llama_log_set(log_cb, nullptr);
    mtmd_helper_log_set(log_cb, nullptr);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    auto * vctx = new vision_ctx();
    vctx->n_threads = nThreads > 0 ? nThreads : 4;

    vctx->model = llama_model_load_from_file(model_path.c_str(), mparams);
    if (vctx->model == nullptr) {
        LOGE("failed to load model: %s", model_path.c_str());
        delete vctx;
        return 0;
    }
    vctx->vocab = llama_model_get_vocab(vctx->model);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 4096;
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = vctx->n_threads;
    cparams.n_threads_batch = vctx->n_threads;
    cparams.no_perf = true;

    vctx->lctx = llama_init_from_model(vctx->model, cparams);
    if (vctx->lctx == nullptr) {
        LOGE("failed to create context");
        llama_model_free(vctx->model);
        delete vctx;
        return 0;
    }

    mtmd_context_params tparams = mtmd_context_params_default();
    tparams.use_gpu = false;
    tparams.print_timings = false;
    tparams.n_threads = vctx->n_threads;
    tparams.batch_max_tokens = 1024;

    vctx->mctx = mtmd_init_from_file(mmproj_path.c_str(), vctx->model, tparams);
    if (vctx->mctx == nullptr) {
        LOGE("failed to load mmproj: %s", mmproj_path.c_str());
        llama_free(vctx->lctx);
        llama_model_free(vctx->model);
        delete vctx;
        return 0;
    }

    LOGI("marker=%s support_vision=%d support_audio=%d",
         mtmd_get_marker(vctx->mctx),
         (int) mtmd_support_vision(vctx->mctx),
         (int) mtmd_support_audio(vctx->mctx));
    LOGI("vision loaded model=%s mmproj=%s", model_path.c_str(), mmproj_path.c_str());
    return reinterpret_cast<jlong>(vctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_dsh_localkb_LocalVision_nativeDescribe(
        JNIEnv * env, jobject /*thiz*/, jlong handle,
        jbyteArray rgb, jint width, jint height,
        jstring jprompt, jint maxTokens) {
    auto * vctx = reinterpret_cast<vision_ctx *>(handle);
    if (vctx == nullptr || vctx->mctx == nullptr) {
        return env->NewStringUTF("（本地视觉模型未加载）");
    }

    const jsize n = env->GetArrayLength(rgb);
    std::vector<unsigned char> pixels(static_cast<size_t>(n));
    env->GetByteArrayRegion(rgb, 0, n, reinterpret_cast<jbyte *>(pixels.data()));

    mtmd_bitmap * bitmap = mtmd_bitmap_init(
            static_cast<uint32_t>(width), static_cast<uint32_t>(height), pixels.data());
    if (bitmap == nullptr) {
        return env->NewStringUTF("（图片转换失败）");
    }

    const std::string marker = mtmd_get_marker(vctx->mctx);
    const std::string user_text = jstr_to_std(env, jprompt);
    const std::string prompt =
        std::string("<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n") +
        "<|im_start|>user\n" + marker + "\n" + user_text + "<|im_end|>\n" +
        "<|im_start|>assistant\n";
    LOGI("prompt_len=%d", (int) prompt.size());
    mtmd_input_text text{};
    text.text = prompt.c_str();
    text.text_len = prompt.size();
    text.add_special = true;
    text.parse_special = true;

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    const mtmd_bitmap * bmp_ptr = bitmap;
    const int32_t tok_ret = mtmd_tokenize(vctx->mctx, chunks, &text, &bmp_ptr, 1);
    if (tok_ret != 0) {
        LOGE("mtmd_tokenize failed: %d", tok_ret);
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap);
        return env->NewStringUTF("（图片编码失败）");
    }

    llama_pos n_past = 0;
    const int32_t eval_ret = mtmd_helper_eval_chunks(
            vctx->mctx, vctx->lctx, chunks, 0, 0, 512, true, &n_past);
    if (eval_ret != 0) {
        LOGE("mtmd_helper_eval_chunks failed: %d", eval_ret);
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bitmap);
        return env->NewStringUTF("（图片理解失败）");
    }

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    std::string out;
    char piece[256];
    const int max_new = maxTokens > 0 ? maxTokens : 256;
    for (int i = 0; i < max_new; ++i) {
        const llama_token tok = llama_sampler_sample(smpl, vctx->lctx, -1);
        if (llama_vocab_is_eog(vctx->vocab, tok)) break;
        const int len = llama_token_to_piece(vctx->vocab, tok, piece, sizeof(piece), 0, true);
        if (len > 0) {
            out.append(piece, len);
            if (out.find("<|im_end|>") != std::string::npos ||
                out.find("<|endoftext|>") != std::string::npos) break;
        }
        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(&tok), 1);
        if (llama_decode(vctx->lctx, batch) != 0) break;
    }

    llama_sampler_free(smpl);
    mtmd_input_chunks_free(chunks);
    mtmd_bitmap_free(bitmap);

    // 去掉模型可能带出的特殊标记
    for (const char * marker : {"<|im_end|>", "<|endoftext|>", "<|im_start|>"}) {
        size_t pos;
        while ((pos = out.find(marker)) != std::string::npos) out.erase(pos, strlen(marker));
    }

    LOGI("described %d chars", (int) out.size());
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_dsh_localkb_LocalVision_nativeIsReady(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * vctx = reinterpret_cast<vision_ctx *>(handle);
    return (vctx != nullptr && vctx->mctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_io_dsh_localkb_LocalVision_nativeFree(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * vctx = reinterpret_cast<vision_ctx *>(handle);
    if (vctx == nullptr) return;
    if (vctx->mctx) mtmd_free(vctx->mctx);
    if (vctx->lctx) llama_free(vctx->lctx);
    if (vctx->model) llama_model_free(vctx->model);
    delete vctx;
    LOGI("vision freed");
}
