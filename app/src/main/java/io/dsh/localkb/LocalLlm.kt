package io.dsh.localkb

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 端侧对话模型（llama.cpp + GGUF）。
 * 首次使用会加载模型（1–3 秒），之后常驻内存。
 */
object LocalLlm {

    init {
        System.loadLibrary("llama_bridge")
    }

    interface TokenSink {
        /** 返回 false 表示请求停止生成。 */
        fun onToken(text: String): Boolean
    }

    private external fun nativeVersion(): String
    private external fun nativeLoad(path: String, nThreads: Int, nCtx: Int): Long
    private external fun nativeFree(handle: Long)
    private external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temp: Float,
        topP: Float,
        sink: TokenSink?
    ): String

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var loadedPath: String? = null

    val version: String by lazy { runCatching { nativeVersion() }.getOrDefault("unavailable") }

    fun isReady(): Boolean = handle != 0L

    fun loadedModelPath(): String? = loadedPath

    @Synchronized
    fun load(modelPath: String, threads: Int = 4, nCtx: Int = 4096): Boolean {
        if (handle != 0L && loadedPath == modelPath) return true
        unload()
        val t0 = System.currentTimeMillis()
        val h = nativeLoad(modelPath, threads, nCtx)
        if (h == 0L) return false
        handle = h
        loadedPath = modelPath
        Log.i("LocalLlm", "loaded in ${System.currentTimeMillis() - t0}ms: $modelPath")
        return true
    }

    @Synchronized
    fun unload() {
        if (handle != 0L) {
            runCatching { nativeFree(handle) }
            handle = 0L
            loadedPath = null
        }
    }

    private const val N_CTX = 4096
    private const val SAFETY = 220

    /** 保守估算 token：CJK 约 1 字 1 token，ASCII 约 4 字符 1 token。 */
    fun estimateTokens(text: String): Int {
        var tokens = 0.0
        var asciiRun = 0
        for (ch in text) {
            if (ch.code < 128) {
                asciiRun++
            } else {
                tokens += asciiRun / 4.0 + 1.0
                asciiRun = 0
            }
        }
        tokens += asciiRun / 4.0
        return tokens.toInt() + 1
    }

    /**
     * 在 n_ctx 内组装 ChatML：优先保留当前问题，其次系统提示，最后按从新到旧追加历史。
     * 超出预算时对问题/系统提示做尾部截断，并在日志里记录被丢弃的历史条数。
     */
    private fun chatMl(
        system: String,
        history: List<Pair<String, String>>,
        user: String,
        maxTokens: Int,
    ): String {
        val budget = N_CTX - maxTokens - SAFETY
        var sys = system
        var question = user
        var used = 0

        if (estimateTokens(question) > budget / 2) {
            // 问题本身过大（例如超长附件）：按字符截断到约一半预算
            val keepChars = (budget / 2).coerceAtLeast(200)
            question = question.take(keepChars) + "\n…（内容过长已截断）"
        }
        used += estimateTokens(question)

        if (sys.isNotBlank()) {
            val sysBudget = (budget - used).coerceAtLeast(120)
            if (estimateTokens(sys) > sysBudget) {
                sys = sys.take((sysBudget * 0.9).toInt().coerceAtLeast(100)) + "…"
            }
            used += estimateTokens(sys)
        }

        val kept = ArrayList<Pair<String, String>>()
        var dropped = 0
        for (m in history.takeLast(12).asReversed()) {
            val cost = estimateTokens(m.second) + 8
            if (used + cost > budget) {
                dropped++
                continue
            }
            kept.add(0, m)
            used += cost
        }
        if (dropped > 0) {
            Log.i("LocalLlm", "prompt trimmed: dropped $dropped history msgs, est=$used/${budget}")
        }

        val sb = StringBuilder()
        if (sys.isNotBlank()) {
            sb.append("<|im_start|>system\n").append(sys).append("<|im_end|>\n")
        }
        for ((role, content) in kept) {
            sb.append("<|im_start|>").append(role).append('\n').append(content).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>user\n").append(question).append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    /**
     * 本地对话。onToken 会在生成过程中被反复调用（在调用线程上），可用于流式显示。
     */
    fun chat(
        context: Context,
        system: String,
        history: List<Pair<String, String>>,
        user: String,
        maxTokens: Int = 256,
        temperature: Float = 0.8f,
        modelFile: java.io.File? = null,
        onToken: ((String) -> Boolean)? = null,
    ): Result<String> {
        val file = modelFile ?: LocalModels.file(context, LocalModels.chat(context))
        if (!file.isFile) return Result.failure(IllegalStateException("本地对话模型未安装"))
        if (!isReady() || loadedPath != file.absolutePath) {
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            if (!load(file.absolutePath, threads, N_CTX)) {
                return Result.failure(IllegalStateException("本地模型加载失败"))
            }
        }
        val prompt = chatMl(system, history, user, maxTokens)
        val sink = onToken?.let { cb ->
            object : TokenSink {
                override fun onToken(text: String): Boolean = cb(text)
            }
        }
        val t0 = System.currentTimeMillis()
        val out = nativeGenerate(handle, prompt, maxTokens, temperature, 0.9f, sink)
        val elapsed = System.currentTimeMillis() - t0
        Log.i("LocalLlm", "generated ${out.length} chars in ${elapsed}ms")
        return Result.success(out.trim())
    }
}
