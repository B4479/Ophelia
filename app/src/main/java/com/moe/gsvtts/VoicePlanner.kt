package com.moe.gsvtts

/**
 * 把 RoleParser 切出来的片段分配到具体声音。
 *
 * 规则：
 * 1. 旁白 → 指定旁白声音（未指定则用池里第一个）
 * 2. 对白且识别到角色 → 该角色绑定的声音（未绑定则按名字哈希稳定分配）
 * 3. 对白未识别角色 → 按文本哈希稳定分配
 *
 * 纯 Kotlin，无 Android 依赖，方便在 JVM 上做单元测试。
 */
class VoicePlanner(
    private val pool: List<String>,
    private val fixedMapping: Map<String, String> = emptyMap(),
    private val narrator: String? = null,
) {

    data class Plan(val text: String, val voice: String, val role: String?, val dialogue: Boolean)

    /** 角色 → 声音 的最终映射（包含本次临时分配的结果）。 */
    val assigned: MutableMap<String, String> = LinkedHashMap(fixedMapping)

    fun narratorVoice(): String? =
        narrator?.takeIf { it in pool } ?: pool.firstOrNull()

    fun voiceForRole(role: String): String? {
        if (pool.isEmpty()) return null
        assigned[role]?.let { if (it in pool) return it }
        val idx = assigned.size % pool.size
        val v = pool[idx]
        assigned[role] = v
        return v
    }

    fun voiceForFreeText(text: String): String? {
        if (pool.isEmpty()) return null
        val idx = (text.hashCode().toLong() and 0x7fffffffL).mod(pool.size.toLong()).toInt()
        return pool[idx]
    }

    fun plan(segments: List<RoleParser.Segment>): List<Plan> {
        val out = ArrayList<Plan>(segments.size)
        for (seg in segments) {
            if (seg.text.isBlank()) continue
            val voice = when {
                !seg.dialogue -> narratorVoice()
                seg.role != null -> voiceForRole(seg.role)
                else -> voiceForFreeText(seg.text)
            } ?: continue
            out.add(Plan(seg.text, voice, seg.role, seg.dialogue))
        }
        return out
    }
}
