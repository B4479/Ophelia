package io.dsh.localkb

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 端侧对话模型（llama.cpp + GGUF）。
 *
 * 提示词优先使用 GGUF 自带的对话模板（`tokenizer.chat_template`），
 * 由 native 调 `llama_chat_apply_template` 渲染；模型没有模板时才回退 ChatML。
 * 这样 Qwen / GLM / Gemma / Llama3 等不同家族都能正常对话，不会把
 * `<|im_start|>` 之类的控制标记当正文吐出来。
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
    private external fun nativeChatTemplate(handle: Long): String
    private external fun nativeTokenCount(handle: Long, text: String): Int
    private external fun nativeApplyTemplate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
    ): String

    private external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temp: Float,
        topP: Float,
        sink: TokenSink?,
        stops: Array<String>,
    ): String

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var loadedPath: String? = null

    @Volatile
    private var chatTemplate: String = ""

    /** 模板里提取出的控制标记，作为停止序列传给 native。 */
    @Volatile
    private var templateStops: Array<String> = emptyArray()

    val version: String by lazy { runCatching { nativeVersion() }.getOrDefault("unavailable") }

    fun isReady(): Boolean = handle != 0L

    fun loadedModelPath(): String? = loadedPath

    /** 当前模型的对话模板描述，用于设置页展示。 */
    fun templateInfo(): String = when {
        !isReady() -> "未加载"
        chatTemplate.isNotBlank() -> "模型自带模板"
        else -> "内置 ChatML"
    }

    @Synchronized
    fun load(modelPath: String, threads: Int = 4, nCtx: Int = 4096): Boolean {
        if (handle != 0L && loadedPath == modelPath) return true
        unload()
        val t0 = System.currentTimeMillis()
        val h = nativeLoad(modelPath, threads, nCtx)
        if (h == 0L) return false
        handle = h
        loadedPath = modelPath
        chatTemplate = runCatching { nativeChatTemplate(h) }.getOrDefault("")
        templateStops = deriveStops(chatTemplate)
        Log.i(
            "LocalLlm",
            "loaded in ${System.currentTimeMillis() - t0}ms: $modelPath | template=" +
                (if (chatTemplate.isBlank()) "none(chatml)" else "builtin") +
                " | stops=${templateStops.size}"
        )
        return true
    }

    @Synchronized
    fun unload() {
        if (handle != 0L) {
            runCatching { nativeFree(handle) }
            handle = 0L
            loadedPath = null
            chatTemplate = ""
            templateStops = emptyArray()
        }
    }

    private const val N_CTX = 4096
    private const val SAFETY = 220

    /** 兜底停止标记（native 侧还有一份，双保险）。 */
    private val FALLBACK_STOPS = arrayOf(
        "<|im_end|>", "<|im_start|>", "<|endoftext|>", "<|eot_id|>",
        "<start_of_turn>", "<end_of_turn>", "</s>", "[gMASK]", "<sop>",
        "<|user|>", "<|assistant|>", "<|system|>",
    )

    /** 从模板文本里抠出 `<|xxx|>` / `<xxx>` / `[gMASK]` 形式的标记。 */
    private fun deriveStops(template: String): Array<String> {
        if (template.isBlank()) return FALLBACK_STOPS
        val found = LinkedHashSet<String>()
        Regex("<\\|[^|<>\\s]{1,32}\\|>").findAll(template).forEach { found.add(it.value) }
        Regex("</?[a-zA-Z_]{2,32}>").findAll(template).forEach { found.add(it.value) }
        Regex("\\[[a-zA-Z]{2,16}\\]").findAll(template).forEach { found.add(it.value) }
        // 只保留“结束”或“开头”类标记会太保守，模板里出现的都当停止标记更安全
        found.addAll(FALLBACK_STOPS)
        return found.toTypedArray()
    }

    /** 去掉正文里残留的模板标记（模型偶尔会吐）。 */
    private fun sanitize(text: String): String {
        var s = text
        for (m in templateStops) {
            if (m.isBlank()) continue
            val idx = s.indexOf(m)
            if (idx >= 0) s = s.substring(0, idx)
        }
        return s.trim()
    }

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

    /** ChatML 兜底（模型没有自带模板时用）。 */
    private fun chatMl(messages: List<Pair<String, String>>): String {
        val sb = StringBuilder()
        for ((role, content) in messages) {
            sb.append("<|im_start|>").append(role).append('\n')
                .append(content).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    private fun render(messages: List<Pair<String, String>>): String {
        if (chatTemplate.isNotBlank()) {
            val roles = messages.map { it.first }.toTypedArray()
            val contents = messages.map { it.second }.toTypedArray()
            val out = runCatching { nativeApplyTemplate(handle, roles, contents) }.getOrDefault("")
            if (out.isNotBlank()) return out
            Log.w("LocalLlm", "applyTemplate failed, fallback to ChatML")
        }
        return chatMl(messages)
    }

    /**
     * 在 n_ctx 内组装提示词：优先当前问题，再系统提示，最后按新→旧追加历史。
     * 超预算时整轮丢弃历史（绝不切断对话结构），仍超长才截断问题本身。
     */
    private fun buildPrompt(
        system: String,
        history: List<Pair<String, String>>,
        user: String,
        maxTokens: Int,
    ): String {
        val budget = N_CTX - maxTokens - SAFETY
        var question = user
        var kept = history.takeLast(12)

        fun assemble(q: String): List<Pair<String, String>> {
            val msgs = ArrayList<Pair<String, String>>()
            if (system.isNotBlank()) msgs.add("system" to system)
            msgs.addAll(kept)
            msgs.add("user" to q)
            return msgs
        }

        // 1) 整轮丢弃历史，直到装得下
        while (true) {
            val promptLen = runCatching { nativeTokenCount(handle, render(assemble(question))) }
                .getOrDefault(-1)
            if (promptLen in 0..budget) return render(assemble(question))
            val promptLenByEstimate = estimateTokens(render(assemble(question)))
            if (promptLen < 0 && promptLenByEstimate <= budget) return render(assemble(question))
            if (kept.isEmpty()) break
            kept = kept.drop(1)
            Log.i("LocalLlm", "prompt over budget ($promptLen), dropped oldest history, left=${kept.size}")
        }

        // 2) 历史清空仍超长 → 截断问题
        var guard = 0
        while (estimateTokens(render(assemble(question))) > budget && guard < 6) {
            val keep = (question.length * 3 / 4).coerceAtLeast(200)
            question = question.take(keep) + "\n…（内容过长已截断）"
            guard++
        }
        return render(assemble(question))
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
        val prompt = buildPrompt(system, history, user, maxTokens)
        val sink = onToken?.let { cb ->
            object : TokenSink {
                override fun onToken(text: String): Boolean = cb(text)
            }
        }
        val t0 = System.currentTimeMillis()
        val out = runCatching {
            nativeGenerate(handle, prompt, maxTokens, temperature, 0.9f, sink, templateStops)
        }.getOrDefault("")

        // native 返回空：可能是提示词超长（已被拒绝）或解码失败，缩减历史重试一次
        if (out.isBlank()) {
            Log.w("LocalLlm", "empty output, retrying with half history")
            val retryPrompt = buildPrompt(system, history.dropLast(history.size / 2 + 1), user, maxTokens)
            val second = runCatching {
                nativeGenerate(handle, retryPrompt, maxTokens, temperature, 0.9f, sink, templateStops)
            }.getOrDefault("")
            val elapsed = System.currentTimeMillis() - t0
            Log.i("LocalLlm", "retry generated ${second.length} chars in ${elapsed}ms")
            return Result.success(sanitize(second))
        }

        val clean = sanitize(out)
        val elapsed = System.currentTimeMillis() - t0
        Log.i("LocalLlm", "generated ${clean.length} chars in ${elapsed}ms")
        return Result.success(clean)
    }
}
