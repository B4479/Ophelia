package io.dsh.localkb

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * 角色卡：只做「导入 + 设定人设」，不做从零创建。
 * 支持 SillyTavern V2 的 PNG 卡（tEXt 块内嵌 base64 JSON）与纯 JSON 卡。
 */
data class CharacterCard(
    val name: String,
    val description: String,
    val personality: String,
    val scenario: String,
    val firstMes: String,
    val mesExample: String,
    val systemPrompt: String,
    val postHistory: String,
    val tags: List<String>,
    val rawJson: String,
    val avatarPath: String?,
)

object CardParser {

    private const val PNG_SIG = "\u0089PNG\r\n\u001a\n"
    private val KEYWORDS = listOf("chara", "ccv3", "character", "card")

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()

    /** 从文件字节解析；PNG 会尝试内嵌 JSON，JSON 直接解析。 */
    fun parse(bytes: ByteArray): CharacterCard? {
        return if (isPng(bytes)) {
            val json = extractPngJson(bytes)
            val avatar = Base64.encodeToString(bytes, Base64.NO_WRAP)
            json?.let { fromJson(it, avatar) }
        } else {
            val text = String(bytes, Charsets.UTF_8).trim()
            if (text.startsWith("{")) fromJson(text, null) else null
        }
    }

    /** 取出卡片内嵌的世界书（data.character_book），没有返回 null。 */
    fun extractCharacterBook(bytes: ByteArray): String? = runCatching {
        val json = if (isPng(bytes)) extractPngJson(bytes) else String(bytes, Charsets.UTF_8).trim()
        if (json == null || !json.startsWith("{")) return@runCatching null
        val root = org.json.JSONObject(json)
        val book = root.optJSONObject("data")?.optJSONObject("character_book")
            ?: root.optJSONObject("character_book")
            ?: return@runCatching null
        val entries = book.opt("entries")
        if (entries == null) return@runCatching null
        val len = when (entries) {
            is org.json.JSONArray -> entries.length()
            is org.json.JSONObject -> entries.length()
            else -> 0
        }
        if (len <= 0) return@runCatching null
        book.toString()
    }.getOrNull()

    /** 读 PNG 的 tEXt / iTXt 块，找角色卡 JSON（base64）。 */
    private fun extractPngJson(bytes: ByteArray): String? {
        var pos = 8
        while (pos + 8 <= bytes.size) {
            val len = beInt(bytes, pos)
            if (len < 0 || pos + 12 + len > bytes.size) break
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val dataStart = pos + 8
            if (type == "tEXt" || type == "iTXt") {
                var i = dataStart
                val end = dataStart + len
                val kw = StringBuilder()
                while (i < end && bytes[i] != 0.toByte()) {
                    kw.append((bytes[i].toInt() and 0xFF).toChar())
                    i++
                }
                if (KEYWORDS.any { kw.toString().equals(it, ignoreCase = true) }) {
                    var text = String(bytes, i + 1, end - (i + 1), Charsets.ISO_8859_1).trim()
                    // iTXt 有额外的压缩/语言字段，宽松处理：取最后一个 \u0000 之后
                    text = text.substringAfterLast('\u0000')
                    val json = runCatching {
                        String(Base64.decode(text, Base64.DEFAULT), Charsets.UTF_8)
                    }.getOrNull() ?: text
                    if (json.trimStart().startsWith("{")) return json
                }
            }
            pos = dataStart + len + 4
        }
        return null
    }

    private fun beInt(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or
            ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)

    fun fromJson(json: String, avatarBase64: String?): CharacterCard? = runCatching {
        val root = JSONObject(json)
        val d = root.optJSONObject("data") ?: root
        val tags = ArrayList<String>()
        d.optJSONArray("tags")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { tags.add(it) }
        }
        CharacterCard(
            name = d.optString("name").ifBlank { "未命名角色" },
            description = d.optString("description"),
            personality = d.optString("personality"),
            scenario = d.optString("scenario"),
            firstMes = d.optString("first_mes").ifBlank { d.optString("first_message") },
            mesExample = d.optString("mes_example"),
            systemPrompt = d.optString("system_prompt"),
            postHistory = d.optString("post_history_instructions"),
            tags = tags,
            rawJson = json,
            avatarPath = avatarBase64,
        )
    }.getOrNull()

    /** 把编辑后的字段合并回原始 JSON（保留未知字段与世界书）。 */
    fun withEdits(card: CharacterCard, fields: Map<String, String>): String = runCatching {
        val root = JSONObject(card.rawJson)
        val hasData = root.optJSONObject("data") != null
        val target = if (hasData) root.getJSONObject("data") else root
        for ((k, v) in fields) target.put(k, v)
        if (hasData) {
            root.put("data", target)
            root.put("spec", root.optString("spec", "chara_card_v2"))
            root.put("spec_version", root.optString("spec_version", "2.0"))
        }
        root.toString(2)
    }.getOrElse { card.rawJson }

    /** 从零新建角色卡时生成 V2 JSON。 */
    fun buildJson(
        name: String,
        systemPrompt: String,
        notes: String,
        scenario: String,
        description: String,
        firstMes: String,
    ): String {
        val data = JSONObject().apply {
            put("name", name)
            put("description", description)
            put("personality", "")
            put("scenario", scenario)
            put("first_mes", firstMes)
            put("mes_example", "")
            put("system_prompt", systemPrompt)
            put("post_history_instructions", "")
            put("creator_notes", notes)
            put("tags", JSONArray())
        }
        val root = JSONObject().apply {
            put("spec", "chara_card_v2")
            put("spec_version", "2.0")
            put("data", data)
        }
        return root.toString(2)
    }

    /** 去掉卡片里常见的 HTML 标签与实体，替换 ST 占位符。 */
    fun cleanCardText(text: String, charName: String, userName: String?): String {
        if (text.isBlank()) return ""
        var s = text
            .replace(Regex("<\\s*br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<\\s*/?\\s*(p|div|blockquote|h[1-6]|li|tr)\\s*[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("\\n{3,}"), "\n\n")
        s = s.replace(Regex("\\{\\{char\\}\\}", RegexOption.IGNORE_CASE), charName)
        if (!userName.isNullOrBlank()) {
            s = s.replace(Regex("\\{\\{user\\}\\}", RegexOption.IGNORE_CASE), userName)
        }
        return s.trim()
    }

    /** 组装聊天用的系统提示。 */
    fun systemPromptFor(card: CharacterCard, userName: String? = null): String {
        fun c(x: String) = cleanCardText(x, card.name, userName)
        val sb = StringBuilder()
        val sys = c(card.systemPrompt)
        if (sys.isNotBlank()) sb.append(sys).append("\n\n")
        sb.append("你要扮演角色「").append(card.name).append("」。")
        c(card.description).takeIf { it.isNotBlank() }?.let { sb.append("\n角色设定：").append(it) }
        c(card.personality).takeIf { it.isNotBlank() }?.let { sb.append("\n性格：").append(it) }
        c(card.scenario).takeIf { it.isNotBlank() }?.let { sb.append("\n场景：").append(it) }
        c(card.mesExample).takeIf { it.isNotBlank() }?.let { sb.append("\n对话示例（仅供风格参考）：\n").append(it) }
        c(card.postHistory).takeIf { it.isNotBlank() }?.let { sb.append("\n").append(it) }
        sb.append("\n始终以「").append(card.name).append("」的第一人称说话，不要跳出角色，不要解释设定。")
        return sb.toString()
    }
}
