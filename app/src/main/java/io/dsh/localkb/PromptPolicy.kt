package io.dsh.localkb

/**
 * Prompt 预算：按“角色扮演 / 工作任务”两种模式分配上下文配额（单位：字符，中文约 1 字 ≈ 1 token）。
 * 顺序遵循：固定信息（人设/助理）> 场景资料（世界书/知识库）> 摘要 > 记忆 > 最近对话。
 */
object PromptPolicy {

    data class Budget(
        val total: Int,
        val persona: Int,
        val lore: Int,
        val kb: Int,
        val summary: Int,
        val memory: Int,
        val history: Int,
    )

    private val CHAT_LOCAL = Budget(
        total = 3400, persona = 900, lore = 700, kb = 0,
        summary = 400, memory = 300, history = 1100,
    )
    private val CHAT_CLOUD = Budget(
        total = 9000, persona = 1500, lore = 2000, kb = 0,
        summary = 1000, memory = 900, history = 3600,
    )
    private val WORK_LOCAL = Budget(
        total = 3400, persona = 700, lore = 0, kb = 1700,
        summary = 300, memory = 200, history = 500,
    )
    private val WORK_CLOUD = Budget(
        total = 9000, persona = 1200, lore = 0, kb = 4000,
        summary = 1000, memory = 700, history = 2100,
    )

    fun forMode(mode: String, provider: String): Budget {
        val local = provider == "local"
        return if (mode == "work") (if (local) WORK_LOCAL else WORK_CLOUD)
        else (if (local) CHAT_LOCAL else CHAT_CLOUD)
    }

    /** 截断到 max 字符，尽量在句号/换行处断开。 */
    fun trim(text: String, max: Int): String {
        if (max <= 0 || text.isEmpty()) return ""
        if (text.length <= max) return text
        val cut = text.substring(0, max)
        val idx = cut.lastIndexOfAny(charArrayOf('。', '\n', '！', '？', '.', ';', '；'))
        val body = if (idx > max * 6 / 10) cut.substring(0, idx + 1) else cut
        return body + "…"
    }

    /** 从最新往旧填历史，不超过配额；返回按时间正序的列表。 */
    fun fitHistory(
        history: List<Pair<String, String>>,
        max: Int,
    ): List<Pair<String, String>> {
        if (max <= 0) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        var used = 0
        for (i in history.indices.reversed()) {
            val (role, content) = history[i]
            val cost = content.length + 8
            if (used + cost > max) break
            out.add(role to content)
            used += cost
        }
        out.reverse()
        return out
    }
}
