package io.dsh.localkb

import android.content.Context
import java.io.File

/**
 * 长会话摘要：把“较早、且已经不在最近窗口内”的对话压缩成一段记要，存库并在后续生成时注入。
 * 两种模式的摘要口径不同：
 *  - 角色扮演：第三人称剧情摘要，保留人物关系、关键事件、承诺与悬念
 *  - 工作任务：结论 / 依据（保留文档名与页码）/ 待办 三段式
 */
object ChatSummarizer {

    /** 最近 N 条不参与摘要，原样进入上下文。 */
    const val KEEP_RECENT = 12

    /** 新累积至少这么多条才值得再摘一次。 */
    const val MIN_NEW = 8

    private fun systemPrompt(mode: String): String = if (mode == "work") {
        "你是会话记要整理器。把下面的工作对话压缩成三段：" +
            "① 已确认的结论 ② 依据（必须保留文档名与页码，如《X》第12页） ③ 待办与未决问题。" +
            "只输出这三段，不要寒暄，不要编造未出现的信息。"
    } else {
        "你是剧情摘要器。把下面的角色扮演对话压缩成第三人称摘要，" +
            "保留人物关系、关键事件、承诺与未解决的悬念，不评论、不评价。" +
            "只输出摘要正文，300 字以内。"
    }

    private fun buildInput(previous: String?, chunk: List<MessageRow>): String {
        val body = chunk.joinToString("\n") { m ->
            (if (m.role == "user") "用户：" else "角色：") + m.content
        }
        val head = if (previous.isNullOrBlank()) "" else "【已有摘要】\n$previous\n\n"
        return head + "【新增对话】\n" + body + "\n\n请输出累积后的完整摘要。"
    }

    /** 手动触发：把「除最近 KEEP_RECENT 条之外」的全部对话压缩（忽略条数阈值）。 */
    fun summarizeNow(
        context: Context,
        db: Db,
        chat: ChatRow,
        provider: String,
        cfg: Llm.Config?,
        localFile: File?,
    ): String? {
        val all = db.messages(chat.id, 10000).filter { it.role == "user" || it.role == "assistant" }
        if (all.size <= KEEP_RECENT + 2) return null
        val latest = db.latestSummary(chat.id)
        val coveredTo = latest?.coveredTo ?: 0L
        val chunk = all.filter { it.id > coveredTo }.dropLast(KEEP_RECENT)
        if (chunk.isEmpty()) return null
        val input = buildInput(latest?.text, chunk)
        val sys = systemPrompt(chat.mode)
        val summary = if (provider == "local") {
            if (localFile == null || !localFile.isFile) return null
            LocalLlm.chat(context, sys, emptyList(), input, maxTokens = 320, modelFile = localFile).getOrNull()
        } else {
            if (cfg == null) return null
            Llm.chat(cfg, sys, emptyList(), input, temperature = 0.3).getOrNull()
        }?.trim()
        if (summary.isNullOrBlank()) return null
        db.addSummary(chat.id, chunk.last().id, summary)
        return summary
    }

    /**
     * 判断是否需要摘要并执行。返回新摘要的文本（没有产生则返回 null）。
     * 失败时静默返回 null，不影响主流程。
     */
    fun maybeSummarize(
        context: Context,
        db: Db,
        chat: ChatRow,
        provider: String,
        cfg: Llm.Config?,
        localFile: File?,
    ): String? {
        val all = db.messages(chat.id, 10000)
            .filter { it.role == "user" || it.role == "assistant" }
        if (all.size <= KEEP_RECENT + MIN_NEW) return null

        val latest = db.latestSummary(chat.id)
        val coveredTo = latest?.coveredTo ?: 0L
        val fresh = all.filter { it.id > coveredTo }
        if (fresh.size < MIN_NEW + KEEP_RECENT) return null

        val chunk = fresh.dropLast(KEEP_RECENT)
        if (chunk.size < MIN_NEW) return null

        val input = buildInput(latest?.text, chunk)
        val sys = systemPrompt(chat.mode)

        val summary = if (provider == "local") {
            if (localFile == null || !localFile.isFile) return null
            LocalLlm.chat(context, sys, emptyList(), input, maxTokens = 320, modelFile = localFile)
                .getOrNull()
        } else {
            if (cfg == null) return null
            Llm.chat(cfg, sys, emptyList(), input, temperature = 0.3).getOrNull()
        }?.trim()

        if (summary.isNullOrBlank()) return null
        db.addSummary(chat.id, chunk.last().id, summary)
        db.log("summary", chunk.last().id, provider, "covered=${chunk.size}")
        return summary
    }
}
