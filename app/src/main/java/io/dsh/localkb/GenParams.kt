package io.dsh.localkb

import org.json.JSONArray
import org.json.JSONObject

/**
 * ST 风格生成参数：每个会话可单独覆盖，默认跟随全局。
 * 云端生效：temperature / top_p / max_tokens / stop / frequency_penalty / presence_penalty
 * 本地生效：temperature / top_p / max_tokens（llama.cpp 当前只接了这几项）
 */
data class GenParams(
    val temperature: Double = 0.85,
    val topP: Double = 0.95,
    val topK: Int = 40,
    val maxTokens: Int = 256,
    val repPenalty: Double = 1.1,
    val stop: List<String> = emptyList(),
    val followGlobal: Boolean = true,
    /** ST 预设里的系统提示词正文（仅作人设前缀，不改变其它组装顺序）。 */
    val systemPrompt: String? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("temperature", temperature); put("top_p", topP); put("top_k", topK)
        put("max_tokens", maxTokens); put("rep_penalty", repPenalty)
        put("stop", JSONArray(stop)); put("follow_global", followGlobal)
        if (!systemPrompt.isNullOrBlank()) put("system_prompt", systemPrompt)
    }.toString()

    companion object {
        fun from(json: String?): GenParams {
            if (json.isNullOrBlank()) return GenParams()
            return runCatching {
                val o = JSONObject(json)
                val stop = ArrayList<String>()
                o.optJSONArray("stop")?.let { a -> for (i in 0 until a.length()) stop.add(a.optString(i)) }
                GenParams(
                    temperature = o.optDouble("temperature", 0.85),
                    topP = o.optDouble("top_p", 0.95),
                    topK = o.optInt("top_k", 40),
                    maxTokens = o.optInt("max_tokens", 256),
                    repPenalty = o.optDouble("rep_penalty", 1.1),
                    stop = stop,
                    followGlobal = o.optBoolean("follow_global", true),
                    systemPrompt = o.optString("system_prompt").takeIf { it.isNotBlank() },
                )
            }.getOrDefault(GenParams())
        }

        /** 解析 SillyTavern 预设 JSON（只取采样参数，忽略 prompt 模板）。 */
        fun fromStPreset(json: String): GenParams? = runCatching {
            val o = JSONObject(json)
            val stop = ArrayList<String>()
            (o.opt("stop") ?: o.opt("stop_sequence"))?.let { v ->
                when (v) {
                    is JSONArray -> for (i in 0 until v.length()) {
                        v.optString(i).takeIf { it.isNotBlank() }?.let { stop.add(it) }
                    }
                    is String -> v.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { stop.add(it) }
                }
            }
            GenParams(
                temperature = o.optDouble("temperature", 0.85),
                topP = o.optDouble("top_p", 0.95),
                topK = o.optInt("top_k", 40),
                maxTokens = o.optInt("max_tokens", o.optInt("max_length", 256)).coerceIn(32, 4096),
                repPenalty = o.optDouble("repetition_penalty", o.optDouble("rep_pen", 1.1)),
                stop = stop.take(8),
                followGlobal = false,
                systemPrompt = extractMainPrompt(o),
            )
        }.getOrNull()

        /** 从 ST 预设里挑“主提示词”正文：prompts[name=main] > system_prompt > story_string。 */
        private fun extractMainPrompt(o: JSONObject): String? {
            val prompts = o.optJSONArray("prompts")
            if (prompts != null) {
                for (i in 0 until prompts.length()) {
                    val p = prompts.optJSONObject(i) ?: continue
                    val name = p.optString("name").lowercase()
                    if (name == "main" || name == "main prompt" || name == "系统提示" || name == "system prompt") {
                        val content = p.optString("content")
                        if (content.isNotBlank()) return content
                    }
                }
            }
            o.optString("system_prompt").takeIf { it.isNotBlank() }?.let { return it }
            o.optString("story_string").takeIf { it.isNotBlank() }?.let { return it }
            return null
        }
    }
}

object GenParamsStore {

    private const val KEY_STICKY = "lore_sticky"

    fun stickyEnabled(db: Db): Boolean = db.getSetting(KEY_STICKY) != "0"

    fun setSticky(db: Db, on: Boolean) = db.setSetting(KEY_STICKY, if (on) "1" else "0")

    private const val KEY_GLOBAL = "gen_global"
    private fun chatKey(chatId: Long) = "gen_chat_$chatId"

    fun global(db: Db): GenParams = GenParams.from(db.getSetting(KEY_GLOBAL))

    fun saveGlobal(db: Db, p: GenParams) = db.setSetting(KEY_GLOBAL, p.toJson())

    /** 会话自己的参数；followGlobal 或没存过时返回 null，表示跟随全局。 */
    fun chatOverride(db: Db, chatId: Long): GenParams? {
        val raw = db.getSetting(chatKey(chatId)) ?: return null
        val p = GenParams.from(raw)
        return if (p.followGlobal) null else p
    }

    fun saveChat(db: Db, chatId: Long, p: GenParams) = db.setSetting(chatKey(chatId), p.toJson())

    fun clearChat(db: Db, chatId: Long) = db.setSetting(chatKey(chatId), "")

    /** 实际生效参数：会话覆盖优先；工作任务温度上限 0.6。 */
    fun effective(db: Db, chatId: Long, mode: String): GenParams {
        val base = chatOverride(db, chatId) ?: global(db)
        val t = if (mode == "work" && base.temperature > 0.6) 0.6 else base.temperature
        return base.copy(temperature = t)
    }
}
