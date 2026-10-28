package io.dsh.localkb

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 世界书导入：兼容 SillyTavern / 酒馆（World Info）JSON。
 * 支持两种根结构：
 *   { "entries": { "0": {...}, "1": {...} } }
 *   { "entries": [ {...}, {...} ] }
 *   [ {...}, {...} ]
 * 条目字段：key / comment / content / constant / disable / order / displayIndex
 */
object LoreImport {

    data class ParsedEntry(
        val title: String,
        val content: String,
        val keys: List<String>,
        val constant: Boolean,
        val disabled: Boolean,
        val order: Int,
    )

    data class Parsed(val bookName: String?, val entries: List<ParsedEntry>)

    fun parse(raw: String): Parsed {
        val trimmed = raw.trim()
        val root: Any = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)

        var bookName: String? = null
        var arr: JSONArray? = null

        when (root) {
            is JSONArray -> arr = root
            is JSONObject -> {
                bookName = root.optString("name").takeIf { it.isNotBlank() }
                    ?: root.optString("title").takeIf { it.isNotBlank() }
                val e = root.opt("entries")
                arr = when (e) {
                    is JSONArray -> e
                    is JSONObject -> {
                        val keys = e.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                        JSONArray().also { out -> keys.forEach { out.put(e.getJSONObject(it)) } }
                    }
                    null -> null
                    else -> null
                }
            }
        }

        val list = ArrayList<ParsedEntry>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val content = o.optString("content").trim()
                if (content.isEmpty()) continue
                val keys = ArrayList<String>()
                val k = o.opt("key")
                when (k) {
                    is JSONArray -> for (j in 0 until k.length()) k.optString(j).trim().takeIf { it.isNotEmpty() }?.let { keys.add(it) }
                    is String -> k.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { keys.add(it) }
                    else -> Unit
                }
                val comment = o.optString("comment").trim()
                val title = comment.ifBlank { keys.firstOrNull() ?: "" }
                list.add(
                    ParsedEntry(
                        title = title,
                        content = content,
                        keys = keys.distinct(),
                        constant = o.optBoolean("constant", false),
                        disabled = o.optBoolean("disable", false),
                        order = o.optInt("displayIndex", o.optInt("order", i)),
                    )
                )
            }
        }
        return Parsed(bookName, list.sortedBy { it.order })
    }

    private fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val n = c.getString(idx)
                    if (!n.isNullOrBlank()) return n
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "导入的世界书"
    }

    /** 导入角色卡内嵌世界书：命名为「<卡名> · 世界书」，返回 (bookId, 条数)。 */
    fun importCharacterBook(db: Db, cardName: String, bookJson: String): Pair<Long, Int>? {
        val parsed = parse(bookJson)
        if (parsed.entries.isEmpty()) return null
        val name = parsed.bookName?.takeIf { it.isNotBlank() } ?: "$cardName · 世界书"
        val existing = db.listLorebooks().firstOrNull { it.name == name }
        val bookId = existing?.id ?: db.createLorebook(name)
        val seen = HashSet<String>()
        if (existing != null) {
            db.listLoreEntries(bookId).forEach { seen.add(it.title.trim() + "" + it.content.trim()) }
        }
        var n = 0
        for (e in parsed.entries) {
            val key = e.title.trim() + "" + e.content.trim()
            if (!seen.add(key)) continue
            val mode = when {
                e.constant -> MODE_ALWAYS
                e.keys.isNotEmpty() -> MODE_KEYWORD
                else -> MODE_ALWAYS
            }
            runCatching {
                db.addLoreEntry(
                    bookId, e.title, e.content, mode,
                    if (mode == MODE_KEYWORD) e.keys.joinToString("，") else null
                )
            }.onSuccess { n++ }
        }
        db.touchLorebook(bookId)
        return bookId to n
    }

    data class Result(val bookId: Long, val bookName: String, val imported: Int, val skipped: Int, val merged: Boolean = false)

    fun importInto(context: Context, db: Db, uri: Uri): Result {
        val fileName = displayName(context, uri)
        val raw = context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        } ?: error("无法读取文件")

        val parsed = parse(raw)
        if (parsed.entries.isEmpty()) error("没有解析到任何条目")

        val name = parsed.bookName?.takeIf { it.isNotBlank() }
            ?: fileName.substringBeforeLast('.').ifBlank { "导入的世界书" }
        // 同名集锦已存在就合并，避免重复导入翻倍
        val existingBook = db.listLorebooks().firstOrNull { it.name == name }
        val bookId = existingBook?.id ?: db.createLorebook(name)
        val seen = HashSet<String>()
        if (existingBook != null) {
            db.listLoreEntries(bookId).forEach { e ->
                seen.add(e.title.trim() + "" + e.content.trim())
            }
        }

        var imported = 0
        var skipped = 0
        for (e in parsed.entries) {
            val key = e.title.trim() + "" + e.content.trim()
            if (seen.add(key).not()) { skipped++; continue }
            val mode = when {
                e.constant -> MODE_ALWAYS
                e.keys.isNotEmpty() -> MODE_KEYWORD
                else -> MODE_ALWAYS
            }
            runCatching {
                val id = db.addLoreEntry(
                    bookId,
                    e.title,
                    e.content,
                    mode = mode,
                    keys = if (mode == MODE_KEYWORD) e.keys.joinToString("，") else null,
                )
                if (e.disabled) db.updateLoreEntry(id, e.title, e.content, false, mode,
                    if (mode == MODE_KEYWORD) e.keys.joinToString("，") else null, null)
                imported++
            }.onFailure { skipped++ }
        }
        db.touchLorebook(bookId)
        return Result(bookId, name, imported, skipped, merged = existingBook != null)
    }
}
