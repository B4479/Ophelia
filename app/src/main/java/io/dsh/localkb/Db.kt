package io.dsh.localkb

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Kb(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val note: String? = null,
    val topK: Int = 5,
)

data class ChatRow(
    val id: Long,
    val mode: String,
    val title: String,
    val characterId: Long?,
    val kbId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val originChatId: Long? = null,
    val branchFromMessageId: Long? = null,
    val kbs: String? = null,
    val assistantName: String? = null,
    val assistantNote: String? = null,
    val assistantPrompt: String? = null,
    val voiceId: String? = null,
    val assistantAvatar: String? = null,
    val bgPath: String? = null,
    val bgBlur: Int = 8,
    val bgDim: Int = 60,
    val lorebookId: Long? = null,
)

data class Lorebook(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class LoreEntry(
    val id: Long,
    val bookId: Long,
    val title: String,
    val content: String,
    val enabled: Boolean,
    val sort: Int,
    val createdAt: Long,
    val mode: String = MODE_ALWAYS,   // always / keyword / vector
    val keys: String? = null,
    val emb: FloatArray? = null,
)

data class MessageRow(
    val id: Long,
    val chatId: Long,
    val role: String,
    val content: String,
    val createdAt: Long,
    val parentId: Long? = null,
    val model: String? = null,
    val edited: Boolean = false,
    val citations: String? = null,
    val attachments: String? = null,
)

data class DebugLogRow(
    val id: Long,
    val kind: String,
    val messageId: Long?,
    val model: String?,
    val detail: String?,
    val createdAt: Long,
)

data class CharacterRow(
    val id: Long,
    val name: String,
    val avatar: String?,
    val cardJson: String,
    val voiceId: String?,
    val createdAt: Long
)

data class DocItem(
    val id: Long,
    val kbId: Long,
    val name: String,
    val pages: Int,
    val chars: Int,
    val status: String,
    val createdAt: Long,
    val srcPath: String? = null,
    val pagesDone: Int = 0,
    val kind: String = "pdf",
)

data class StoredChunk(
    val id: Long,
    val docId: Long,
    val docName: String,
    val page: Int,
    val text: String,
    val emb: FloatArray
)

data class Hit(
    val docId: Long = 0,
    val docName: String,
    val page: Int,
    val text: String,
    val score: Float,
    val dense: Float = 0f,
)

data class CloudModel(
    val id: Long,
    val type: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val notes: String? = null,
)

data class ModelSource(
    val id: Long,
    val name: String,
    val url: String,
    val builtin: Boolean = false,
)

private fun floatsToBytes(a: FloatArray): ByteArray {
    val bb = ByteBuffer.allocate(a.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (v in a) bb.putFloat(v)
    return bb.array()
}

private fun bytesToFloats(b: ByteArray): FloatArray {
    val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
    val out = FloatArray(b.size / 4)
    for (i in out.indices) out[i] = bb.float
    return out
}

const val MODE_ALWAYS = "always"
const val MODE_KEYWORD = "keyword"
const val MODE_VECTOR = "vector"

class Db(context: Context) : SQLiteOpenHelper(context, "localkb.db", null, 15) {

    /** 首次安装引导：已有任一配置（云端 API / 本地对话模型）就直接标记完成。 */
    fun ensureOnboardingState(hasModel: Boolean) {
        if (getSetting("onboarding_done") != null) return
        setSetting("onboarding_done", if (hasModel) "1" else "0")
    }

    /** 世界书三模式列的自愈式补列（防止版本号已升但列没加上）。 */
    fun ensureLoreColumns() {
        val cols = HashSet<String>()
        runCatching {
            readableDatabase.rawQuery("PRAGMA table_info(lore_entry)", null).use { c ->
                while (c.moveToNext()) cols.add(c.getString(1))
            }
        }
        runCatching {
            if ("mode" !in cols) writableDatabase.execSQL("ALTER TABLE lore_entry ADD COLUMN mode TEXT NOT NULL DEFAULT 'always'")
            if ("keys" !in cols) writableDatabase.execSQL("ALTER TABLE lore_entry ADD COLUMN keys TEXT")
            if ("emb" !in cols) writableDatabase.execSQL("ALTER TABLE lore_entry ADD COLUMN emb BLOB")
            android.util.Log.i("DbSchema", "lore columns ensured: $cols")
        }.onFailure { android.util.Log.e("DbSchema", "ensure lore columns failed: $it") }
    }

    /** 启动自检：关键列缺失时记日志并尽力补齐。 */
    fun verifySchema() {
        ensureLoreColumns()
        val required = mapOf(
            "chat" to listOf("kbs", "voice_id", "assistant_avatar", "bg_path", "bg_blur", "bg_dim", "lorebook_id"),
            "message" to listOf("parent_id", "model", "edited", "deleted", "citations", "attachments"),
            "kb" to listOf("note", "top_k")
        )
        val missing = ArrayList<String>()
        required.forEach { (table, cols) ->
            val existing = HashSet<String>()
            runCatching {
                readableDatabase.rawQuery("PRAGMA table_info($table)", null).use { c ->
                    while (c.moveToNext()) existing.add(c.getString(1))
                }
            }
            cols.filter { it !in existing }.forEach { missing.add("$table.$it") }
        }
        if (missing.isNotEmpty()) {
            android.util.Log.e("DbSchema", "missing columns: $missing")
            log("schema_missing", null, null, missing.joinToString(","))
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE kb(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created_at INTEGER NOT NULL, note TEXT, top_k INTEGER NOT NULL DEFAULT 5)")
        db.execSQL("CREATE TABLE doc(id INTEGER PRIMARY KEY AUTOINCREMENT, kb_id INTEGER NOT NULL, name TEXT NOT NULL, pages INTEGER NOT NULL DEFAULT 0, chars INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'pending', created_at INTEGER NOT NULL, src_path TEXT, pages_done INTEGER NOT NULL DEFAULT 0, kind TEXT NOT NULL DEFAULT 'pdf')")
        db.execSQL("CREATE TABLE chunk(id INTEGER PRIMARY KEY AUTOINCREMENT, kb_id INTEGER NOT NULL, doc_id INTEGER NOT NULL, page INTEGER NOT NULL DEFAULT 0, text TEXT NOT NULL, emb BLOB NOT NULL)")
        db.execSQL("CREATE TABLE setting(k TEXT PRIMARY KEY, v TEXT)")
        db.execSQL("CREATE TABLE cloud_model(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, name TEXT NOT NULL, base_url TEXT NOT NULL, api_key TEXT NOT NULL, model TEXT NOT NULL, notes TEXT, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE model_source(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, url TEXT NOT NULL, builtin INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_chunk_kb ON chunk(kb_id)")
        createLoreTables(db)
        runCatching { db.execSQL("ALTER TABLE lore_entry ADD COLUMN mode TEXT NOT NULL DEFAULT 'always'") }
        runCatching { db.execSQL("ALTER TABLE lore_entry ADD COLUMN keys TEXT") }
        runCatching { db.execSQL("ALTER TABLE lore_entry ADD COLUMN emb BLOB") }
        createCharacterTable(db)
        createChatTables(db)
    }

    private fun createLoreTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS lorebook(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS lore_entry(id INTEGER PRIMARY KEY AUTOINCREMENT, book_id INTEGER NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, sort INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_lore_book ON lore_entry(book_id)")
    }

    private fun createChatTables(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS chat(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, mode TEXT NOT NULL, title TEXT NOT NULL, " +
                "character_id INTEGER, kb_id INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, " +
                "origin_chat_id INTEGER, branch_from_message_id INTEGER, " +
                "kbs TEXT, assistant_name TEXT, assistant_note TEXT, assistant_prompt TEXT, voice_id TEXT, " +
                "assistant_avatar TEXT, bg_path TEXT, bg_blur INTEGER NOT NULL DEFAULT 8, " +
                "bg_dim INTEGER NOT NULL DEFAULT 60, lorebook_id INTEGER)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS message(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL, role TEXT NOT NULL, " +
                "content TEXT NOT NULL, created_at INTEGER NOT NULL, " +
                "parent_id INTEGER, model TEXT, edited INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, citations TEXT, attachments TEXT)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_message_chat ON message(chat_id)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS debug_log(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, " +
                "message_id INTEGER, model TEXT, detail TEXT, created_at INTEGER NOT NULL)"
        )
    }

    private fun createCharacterTable(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS character(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, avatar TEXT, " +
                "card_json TEXT NOT NULL, voice_id TEXT, created_at INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createCharacterTable(db)
        if (oldVersion < 3) createChatTables(db)
        val migrationErrors = ArrayList<String>()
        if (oldVersion < 15) {
            listOf(
                "CREATE TABLE IF NOT EXISTS lorebook(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS lore_entry(id INTEGER PRIMARY KEY AUTOINCREMENT, book_id INTEGER NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, sort INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)",
                "CREATE INDEX IF NOT EXISTS idx_lore_book ON lore_entry(book_id)",
                "ALTER TABLE chat ADD COLUMN lorebook_id INTEGER"
            ).forEach { sql -> runCatching { db.execSQL(sql) }.onFailure { migrationErrors.add("$sql :: ${it.message}") } }
        }
        if (oldVersion < 15) {
            listOf(
                "ALTER TABLE lore_entry ADD COLUMN mode TEXT NOT NULL DEFAULT 'always'",
                "ALTER TABLE lore_entry ADD COLUMN keys TEXT",
                "ALTER TABLE lore_entry ADD COLUMN emb BLOB"
            ).forEach { sql ->
                runCatching { db.execSQL(sql) }.onFailure { android.util.Log.e("DbMigration", "lore: $it") }
            }
        }
        if (oldVersion < 14) {
            listOf(
                "ALTER TABLE chat ADD COLUMN bg_path TEXT",
                "ALTER TABLE chat ADD COLUMN bg_blur INTEGER NOT NULL DEFAULT 8",
                "ALTER TABLE chat ADD COLUMN bg_dim INTEGER NOT NULL DEFAULT 60"
            ).forEach { sql ->
                runCatching { db.execSQL(sql) }.onFailure { migrationErrors.add("$sql :: ${it.message}") }
            }
        }
        if (migrationErrors.isNotEmpty()) {
            migrationErrors.forEach { android.util.Log.e("DbMigration", "failed: $it") }
            runCatching {
                db.execSQL("CREATE TABLE IF NOT EXISTS debug_log(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, message_id INTEGER, model TEXT, detail TEXT, created_at INTEGER)")
                db.execSQL(
                    "INSERT INTO debug_log(kind, message_id, model, detail, created_at) VALUES(?,?,?,?,?)",
                    arrayOf<Any?>("migration_failed", oldVersion.toLong(), null, migrationErrors.joinToString(" | ").take(500), System.currentTimeMillis())
                )
            }
        }
        if (oldVersion < 13) {
            runCatching { db.execSQL("CREATE TABLE IF NOT EXISTS cloud_model(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, name TEXT NOT NULL, base_url TEXT NOT NULL, api_key TEXT NOT NULL, model TEXT NOT NULL, notes TEXT, created_at INTEGER NOT NULL)") }
            runCatching { db.execSQL("CREATE TABLE IF NOT EXISTS model_source(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, url TEXT NOT NULL, builtin INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)") }
        }
        if (oldVersion < 12) {
            runCatching { db.execSQL("ALTER TABLE message ADD COLUMN attachments TEXT") }
        }
        if (oldVersion < 11) {
            runCatching { db.execSQL("ALTER TABLE chat ADD COLUMN assistant_avatar TEXT") }
        }
        if (oldVersion < 10) {
            runCatching { db.execSQL("ALTER TABLE chat ADD COLUMN kbs TEXT") }
            runCatching { db.execSQL("ALTER TABLE chat ADD COLUMN assistant_name TEXT") }
            runCatching { db.execSQL("ALTER TABLE chat ADD COLUMN assistant_note TEXT") }
            runCatching { db.execSQL("ALTER TABLE chat ADD COLUMN assistant_prompt TEXT") }
            runCatching { db.execSQL("ALTER TABLE chat ADD COLUMN voice_id TEXT") }
        }
        if (oldVersion < 9) {
            runCatching { db.execSQL("ALTER TABLE kb ADD COLUMN top_k INTEGER NOT NULL DEFAULT 5") }
        }
        if (oldVersion < 8) {
            runCatching { db.execSQL("ALTER TABLE kb ADD COLUMN note TEXT") }
        }
        if (oldVersion < 7) {
            runCatching { db.execSQL("ALTER TABLE doc ADD COLUMN kind TEXT NOT NULL DEFAULT 'pdf'") }
        }
        if (oldVersion < 6) {
            runCatching { db.execSQL("ALTER TABLE message ADD COLUMN citations TEXT") }
        }
        if (oldVersion < 5) {
            runCatching { db.execSQL("ALTER TABLE message ADD COLUMN parent_id INTEGER") }
            runCatching { db.execSQL("ALTER TABLE message ADD COLUMN model TEXT") }
            runCatching { db.execSQL("ALTER TABLE message ADD COLUMN edited INTEGER NOT NULL DEFAULT 0") }
            runCatching { db.execSQL("ALTER TABLE message ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0") }
            runCatching { db.execSQL("ALTER TABLE chat ADD COLUMN origin_chat_id INTEGER") }
            runCatching { db.execSQL("ALTER TABLE chat ADD COLUMN branch_from_message_id INTEGER") }
            runCatching {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS debug_log(" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, " +
                        "message_id INTEGER, model TEXT, detail TEXT, created_at INTEGER NOT NULL)"
                )
            }
        }
        if (oldVersion < 4) {
            runCatching { db.execSQL("ALTER TABLE doc ADD COLUMN src_path TEXT") }
            runCatching { db.execSQL("ALTER TABLE doc ADD COLUMN pages_done INTEGER NOT NULL DEFAULT 0") }
        }
    }

    fun setDocSource(docId: Long, path: String) {
        val cv = ContentValues().apply {
            put("src_path", path)
            put("status", "queued")
        }
        writableDatabase.update("doc", cv, "id=?", arrayOf(docId.toString()))
    }

    fun docById(id: Long): DocItem? {
        readableDatabase.rawQuery(
            "SELECT id,kb_id,name,pages,chars,status,created_at,src_path,pages_done,kind FROM doc WHERE id=?",
            arrayOf(id.toString())
        ).use { c ->
            if (c.moveToNext()) return DocItem(
                c.getLong(0), c.getLong(1), c.getString(2), c.getInt(3), c.getInt(4),
                c.getString(5), c.getLong(6), c.getString(7), c.getInt(8), c.getString(9)
            )
        }
        return null
    }

    fun setDocPages(docId: Long, pages: Int) {
        val cv = ContentValues().apply { put("pages", pages) }
        writableDatabase.update("doc", cv, "id=?", arrayOf(docId.toString()))
    }

    fun updateProgress(docId: Long, pagesDone: Int, chars: Int, status: String) {
        val cv = ContentValues().apply {
            put("pages_done", pagesDone)
            put("chars", chars)
            put("status", status)
        }
        writableDatabase.update("doc", cv, "id=?", arrayOf(docId.toString()))
    }

    fun deleteChunksFrom(docId: Long, page: Int) {
        writableDatabase.delete("chunk", "doc_id=? AND page>=?", arrayOf(docId.toString(), page.toString()))
    }

    fun pendingDocs(): List<DocItem> {
        val out = ArrayList<DocItem>()
        readableDatabase.rawQuery(
            "SELECT id,kb_id,name,pages,chars,status,created_at,src_path,pages_done,kind FROM doc " +
                "WHERE status IN ('queued','parsing','embedding','paused') AND src_path IS NOT NULL",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    DocItem(
                        c.getLong(0), c.getLong(1), c.getString(2), c.getInt(3), c.getInt(4),
                        c.getString(5), c.getLong(6), c.getString(7), c.getInt(8), c.getString(9)
                    )
                )
            }
        }
        return out
    }

    fun createChat(
        mode: String,
        title: String,
        characterId: Long?,
        kbId: Long?,
        kbs: String? = null,
        assistantName: String? = null,
        assistantNote: String? = null,
        assistantPrompt: String? = null,
        voiceId: String? = null,
        assistantAvatar: String? = null,
        lorebookId: Long? = null,
    ): Long {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("mode", mode)
            put("title", title)
            put("character_id", characterId)
            put("kb_id", kbId)
            put("created_at", now)
            put("updated_at", now)
            put("kbs", kbs ?: kbId?.let { "[$it]" })
            put("assistant_name", assistantName)
            put("assistant_note", assistantNote)
            put("assistant_prompt", assistantPrompt)
            put("voice_id", voiceId)
            put("assistant_avatar", assistantAvatar)
            put("lorebook_id", lorebookId)
        }
        return writableDatabase.insert("chat", null, cv)
    }

    fun updateChatAssistant(id: Long, name: String?, note: String?, prompt: String?, avatar: String? = null) {
        val cv = ContentValues().apply {
            put("assistant_name", name)
            put("assistant_note", note)
            put("assistant_prompt", prompt)
            put("assistant_avatar", avatar)
        }
        writableDatabase.update("chat", cv, "id=?", arrayOf(id.toString()))
    }

    fun updateChatBg(id: Long, path: String?, blur: Int, dim: Int) {
        val cv = ContentValues().apply {
            put("bg_path", path)
            put("bg_blur", blur.coerceIn(0, 25))
            put("bg_dim", dim.coerceIn(0, 85))
        }
        writableDatabase.update("chat", cv, "id=?", arrayOf(id.toString()))
    }

    fun updateChatVoice(id: Long, voiceId: String?) {
        val cv = ContentValues().apply { put("voice_id", voiceId) }
        writableDatabase.update("chat", cv, "id=?", arrayOf(id.toString()))
    }

    fun updateChatKbs(id: Long, kbsJson: String?) {
        val cv = ContentValues().apply { put("kbs", kbsJson) }
        writableDatabase.update("chat", cv, "id=?", arrayOf(id.toString()))
    }

    // ---------- 世界书 ----------
    fun listLorebooks(): List<Lorebook> {
        val out = ArrayList<Lorebook>()
        readableDatabase.rawQuery("SELECT id,name,created_at,updated_at FROM lorebook ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) out.add(Lorebook(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3)))
        }
        return out
    }

    fun lorebookById(id: Long): Lorebook? =
        listLorebooks().firstOrNull { it.id == id }

    fun createLorebook(name: String): Long {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("name", name); put("created_at", now); put("updated_at", now)
        }
        return writableDatabase.insert("lorebook", null, cv)
    }

    fun renameLorebook(id: Long, name: String) {
        val cv = ContentValues().apply { put("name", name); put("updated_at", System.currentTimeMillis()) }
        writableDatabase.update("lorebook", cv, "id=?", arrayOf(id.toString()))
    }

    fun touchLorebook(id: Long) {
        val cv = ContentValues().apply { put("updated_at", System.currentTimeMillis()) }
        writableDatabase.update("lorebook", cv, "id=?", arrayOf(id.toString()))
    }

    fun countChatsUsingLorebook(id: Long): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM chat WHERE lorebook_id=?", arrayOf(id.toString())).use { c ->
            return if (c.moveToNext()) c.getInt(0) else 0
        }
    }

    /** 删除集锦：同时解除会话绑定（历史消息不动）。 */
    fun deleteLorebook(id: Long) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("lore_entry", "book_id=?", arrayOf(id.toString()))
            writableDatabase.delete("lorebook", "id=?", arrayOf(id.toString()))
            val cv = ContentValues().apply { putNull("lorebook_id") }
            writableDatabase.update("chat", cv, "lorebook_id=?", arrayOf(id.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun listLoreEntries(bookId: Long, onlyEnabled: Boolean = false): List<LoreEntry> {
        val out = ArrayList<LoreEntry>()
        val sql = "SELECT id,book_id,title,content,enabled,sort,created_at,mode,keys,emb FROM lore_entry WHERE book_id=?" +
            (if (onlyEnabled) " AND enabled=1" else "") + " ORDER BY sort ASC, id ASC"
        readableDatabase.rawQuery(sql, arrayOf(bookId.toString())).use { c ->
            while (c.moveToNext()) {
                val embBytes = c.getBlob(9)
                out.add(
                    LoreEntry(
                        c.getLong(0), c.getLong(1), c.getString(2), c.getString(3),
                        c.getInt(4) == 1, c.getInt(5), c.getLong(6),
                        mode = c.getString(7) ?: MODE_ALWAYS,
                        keys = c.getString(8),
                        emb = embBytes?.let { bytesToFloats(it) }
                    )
                )
            }
        }
        return out
    }

    fun addLoreEntry(
        bookId: Long,
        title: String,
        content: String,
        mode: String = MODE_ALWAYS,
        keys: String? = null,
        emb: FloatArray? = null,
    ): Long {
        val maxSort = readableDatabase.rawQuery(
            "SELECT COALESCE(MAX(sort),0) FROM lore_entry WHERE book_id=?", arrayOf(bookId.toString())
        ).use { c -> if (c.moveToNext()) c.getInt(0) else 0 }
        val cv = ContentValues().apply {
            put("book_id", bookId); put("title", title); put("content", content)
            put("enabled", 1); put("sort", maxSort + 1); put("created_at", System.currentTimeMillis())
            put("mode", mode); put("keys", keys)
            if (emb != null) put("emb", floatsToBytes(emb))
        }
        val id = writableDatabase.insert("lore_entry", null, cv)
        touchLorebook(bookId)
        return id
    }

    fun updateLoreEntry(
        id: Long,
        title: String,
        content: String,
        enabled: Boolean,
        mode: String = MODE_ALWAYS,
        keys: String? = null,
        emb: FloatArray? = null,
    ) {
        val cv = ContentValues().apply {
            put("title", title); put("content", content); put("enabled", if (enabled) 1 else 0)
            put("mode", mode); put("keys", keys)
            if (emb != null) put("emb", floatsToBytes(emb))
        }
        writableDatabase.update("lore_entry", cv, "id=?", arrayOf(id.toString()))
    }

    fun deleteLoreEntry(id: Long) {
        writableDatabase.delete("lore_entry", "id=?", arrayOf(id.toString()))
    }

    fun setChatLorebook(chatId: Long, lorebookId: Long?) {
        val cv = ContentValues().apply { put("lorebook_id", lorebookId) }
        writableDatabase.update("chat", cv, "id=?", arrayOf(chatId.toString()))
    }

    fun listChats(mode: String): List<ChatRow> {
        val out = ArrayList<ChatRow>()
        readableDatabase.rawQuery(
            "SELECT id,mode,title,character_id,kb_id,created_at,updated_at,origin_chat_id,branch_from_message_id," +
                "kbs,assistant_name,assistant_note,assistant_prompt,voice_id,assistant_avatar,bg_path,bg_blur,bg_dim,lorebook_id " +
                "FROM chat WHERE mode=? AND EXISTS(SELECT 1 FROM message m WHERE m.chat_id=chat.id) " +
                "ORDER BY updated_at DESC", arrayOf(mode)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ChatRow(
                        c.getLong(0), c.getString(1), c.getString(2),
                        if (c.isNull(3)) null else c.getLong(3),
                        if (c.isNull(4)) null else c.getLong(4),
                        c.getLong(5), c.getLong(6),
                        if (c.isNull(7)) null else c.getLong(7),
                        if (c.isNull(8)) null else c.getLong(8),
                        c.getString(9), c.getString(10), c.getString(11), c.getString(12), c.getString(13), c.getString(14),
                    c.getString(15), c.getInt(16), c.getInt(17),
                    if (c.isNull(18)) null else c.getLong(18)
                    )
                )
            }
        }
        return out
    }

    fun chatById(id: Long): ChatRow? {
        readableDatabase.rawQuery(
            "SELECT id,mode,title,character_id,kb_id,created_at,updated_at,origin_chat_id,branch_from_message_id," +
                "kbs,assistant_name,assistant_note,assistant_prompt,voice_id,assistant_avatar,bg_path,bg_blur,bg_dim,lorebook_id " +
                "FROM chat WHERE id=?", arrayOf(id.toString())
        ).use { c ->
            if (c.moveToNext()) {
                return ChatRow(
                    c.getLong(0), c.getString(1), c.getString(2),
                    if (c.isNull(3)) null else c.getLong(3),
                    if (c.isNull(4)) null else c.getLong(4),
                    c.getLong(5), c.getLong(6),
                    if (c.isNull(7)) null else c.getLong(7),
                    if (c.isNull(8)) null else c.getLong(8),
                    c.getString(9), c.getString(10), c.getString(11), c.getString(12), c.getString(13), c.getString(14),
                    c.getString(15), c.getInt(16), c.getInt(17),
                    if (c.isNull(18)) null else c.getLong(18)
                )
            }
        }
        return null
    }

    fun renameChat(id: Long, title: String) {
        val cv = ContentValues().apply { put("title", title) }
        writableDatabase.update("chat", cv, "id=?", arrayOf(id.toString()))
    }

    fun touchChat(id: Long, title: String? = null) {
        val cv = ContentValues().apply {
            put("updated_at", System.currentTimeMillis())
            if (title != null) put("title", title)
        }
        writableDatabase.update("chat", cv, "id=?", arrayOf(id.toString()))
    }

    fun deleteEmptyChats() {
        runCatching {
            writableDatabase.execSQL(
                "DELETE FROM chat WHERE NOT EXISTS(SELECT 1 FROM message m WHERE m.chat_id=chat.id)"
            )
        }
    }

    fun messageCount(chatId: Long): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM message WHERE chat_id=? AND deleted=0",
            arrayOf(chatId.toString())
        ).use { c -> if (c.moveToNext()) return c.getInt(0) }
        return 0
    }

    fun deleteChat(id: Long) {
        writableDatabase.delete("message", "chat_id=?", arrayOf(id.toString()))
        writableDatabase.delete("chat", "id=?", arrayOf(id.toString()))
    }

    fun addMessage(
        chatId: Long,
        role: String,
        content: String,
        parentId: Long? = null,
        model: String? = null,
        citations: String? = null,
        attachments: String? = null,
    ): Long {
        val cv = ContentValues().apply {
            put("chat_id", chatId)
            put("role", role)
            put("content", content)
            put("created_at", System.currentTimeMillis())
            put("parent_id", parentId)
            put("model", model)
            put("citations", citations)
            put("attachments", attachments)
        }
        val id = writableDatabase.insert("message", null, cv)
        touchChat(chatId)
        return id
    }

    fun updateMessageContent(id: Long, content: String) {
        val cv = ContentValues().apply {
            put("content", content)
            put("edited", 1)
        }
        writableDatabase.update("message", cv, "id=?", arrayOf(id.toString()))
    }

    fun deleteMessage(id: Long) {
        writableDatabase.delete("message", "id=?", arrayOf(id.toString()))
    }

    /** 删除该消息及其后（同会话、id 更大）的所有消息。 */
    /** 只删除该消息及其树中后代（同问题的其它回答保留）。返回删除条数。 */
    fun deleteMessageTree(chatId: Long, id: Long): Int {
        val children = HashMap<Long, MutableList<Long>>()
        val roots = HashSet<Long>()
        readableDatabase.rawQuery("SELECT id,parent_id FROM message WHERE chat_id=?", arrayOf(chatId.toString())).use { c ->
            while (c.moveToNext()) {
                val mid = c.getLong(0)
                val pid = if (c.isNull(1)) null else c.getLong(1)
                if (pid == null) roots.add(mid) else children.getOrPut(pid) { mutableListOf() }.add(mid)
            }
        }
        val victims = LinkedHashSet<Long>()
        val stack = ArrayDeque<Long>()
        stack.add(id)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            if (!victims.add(cur)) continue
            children[cur]?.forEach { stack.add(it) }
        }
        val ids = victims.toList()
        if (ids.isEmpty()) return 0
        ids.chunked(400).forEach { part ->
            val ph = part.joinToString(",") { "?" }
            writableDatabase.delete("message", "chat_id=? AND id IN ($ph)",
                arrayOf(chatId.toString(), *part.map { it.toString() }.toTypedArray()))
        }
        return ids.size
    }

    fun deleteMessageAndAfter(chatId: Long, id: Long) {
        writableDatabase.delete("message", "chat_id=? AND id>=?", arrayOf(chatId.toString(), id.toString()))
    }

    fun log(kind: String, messageId: Long?, model: String?, detail: String? = null) {
        val cv = ContentValues().apply {
            put("kind", kind)
            put("message_id", messageId)
            put("model", model)
            put("detail", detail)
            put("created_at", System.currentTimeMillis())
        }
        runCatching { writableDatabase.insert("debug_log", null, cv) }
    }

    fun listLogs(limit: Int = 200): List<DebugLogRow> {
        val out = ArrayList<DebugLogRow>()
        readableDatabase.rawQuery(
            "SELECT id,kind,message_id,model,detail,created_at FROM debug_log ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    DebugLogRow(
                        c.getLong(0), c.getString(1),
                        if (c.isNull(2)) null else c.getLong(2),
                        c.getString(3), c.getString(4), c.getLong(5)
                    )
                )
            }
        }
        return out
    }

    fun clearLogs() {
        writableDatabase.delete("debug_log", null, null)
    }

    /** 以某条消息为终点开新分支会话：复制该会话 id<=uptoId 的消息链。 */
    fun branchChat(from: ChatRow, uptoMessageId: Long): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val cv = ContentValues().apply {
                put("mode", from.mode)
                put("title", "↳ " + from.title)
                put("character_id", from.characterId)
                put("kb_id", from.kbId)
                put("created_at", now)
                put("updated_at", now)
                put("origin_chat_id", from.id)
                put("branch_from_message_id", uptoMessageId)
                put("kbs", from.kbs ?: from.kbId?.let { "[$it]" })
                put("assistant_name", from.assistantName)
                put("assistant_note", from.assistantNote)
                put("assistant_prompt", from.assistantPrompt)
                put("voice_id", from.voiceId)
                put("assistant_avatar", from.assistantAvatar)
            }
            val newChatId = db.insertOrThrow("chat", null, cv)

            val rows = ArrayList<MessageRow>()
            db.rawQuery(
                "SELECT id,chat_id,role,content,created_at,parent_id,model,edited,citations,attachments FROM message " +
                    "WHERE chat_id=? AND id<=? AND deleted=0 ORDER BY id ASC",
                arrayOf(from.id.toString(), uptoMessageId.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    rows.add(
                        MessageRow(
                            c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4),
                            if (c.isNull(5)) null else c.getLong(5), c.getString(6), c.getInt(7) == 1, c.getString(8), c.getString(9)
                        )
                    )
                }
            }
            val idMap = HashMap<Long, Long>()
            for (m in rows) {
                val mappedParent = m.parentId?.let { idMap[it] }
                val mcv = ContentValues().apply {
                    put("chat_id", newChatId)
                    put("role", m.role)
                    put("content", m.content)
                    put("created_at", m.createdAt)
                    put("parent_id", mappedParent)
                    put("model", m.model)
                    put("edited", if (m.edited) 1 else 0)
                    put("citations", m.citations)
                    put("attachments", m.attachments)
                }
                idMap[m.id] = db.insertOrThrow("message", null, mcv)
            }
            db.setTransactionSuccessful()
            return newChatId
        } finally {
            db.endTransaction()
        }
    }

    fun messages(chatId: Long, limit: Int = 200): List<MessageRow> {
        val out = ArrayList<MessageRow>()
        readableDatabase.rawQuery(
            "SELECT * FROM (" +
                "SELECT id,chat_id,role,content,created_at,parent_id,model,edited,citations,attachments FROM message " +
                "WHERE chat_id=? AND deleted=0 ORDER BY id DESC LIMIT ?" +
                ") ORDER BY id ASC",
            arrayOf(chatId.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    MessageRow(
                        c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getLong(4),
                        if (c.isNull(5)) null else c.getLong(5), c.getString(6), c.getInt(7) == 1, c.getString(8), c.getString(9)
                    )
                )
            }
        }
        return out
    }

    fun addCharacter(card: CharacterCard, voiceId: String? = null): Long {
        val cv = ContentValues().apply {
            put("name", card.name)
            put("avatar", card.avatarPath)
            put("card_json", card.rawJson)
            put("voice_id", voiceId)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("character", null, cv)
    }

    fun listCharacters(): List<CharacterRow> {
        val out = ArrayList<CharacterRow>()
        readableDatabase.rawQuery(
            "SELECT id,name,avatar,card_json,voice_id,created_at FROM character ORDER BY id DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    CharacterRow(
                        c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                        c.getString(4), c.getLong(5)
                    )
                )
            }
        }
        return out
    }

    fun updateCharacter(id: Long, name: String, cardJson: String, voiceId: String?) {
        val cv = ContentValues().apply {
            put("name", name)
            put("card_json", cardJson)
            put("voice_id", voiceId)
        }
        writableDatabase.update("character", cv, "id=?", arrayOf(id.toString()))
    }

    fun updateCharacterFull(id: Long, name: String, cardJson: String, voiceId: String?, avatar: String?) {
        val cv = ContentValues().apply {
            put("name", name)
            put("card_json", cardJson)
            put("voice_id", voiceId)
            put("avatar", avatar)
        }
        writableDatabase.update("character", cv, "id=?", arrayOf(id.toString()))
    }

    fun updateCharacterVoice(id: Long, voiceId: String?) {
        val cv = ContentValues().apply { put("voice_id", voiceId) }
        writableDatabase.update("character", cv, "id=?", arrayOf(id.toString()))
    }

    fun deleteCharacter(id: Long) {
        writableDatabase.delete("character", "id=?", arrayOf(id.toString()))
    }

    fun createKb(name: String, note: String = ""): Long {
        val cv = ContentValues().apply {
            put("name", name)
            put("note", note)
            put("top_k", 5)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("kb", null, cv)
    }

    fun updateKbTopK(id: Long, topK: Int) {
        val cv = ContentValues().apply { put("top_k", topK.coerceIn(1, 10)) }
        writableDatabase.update("kb", cv, "id=?", arrayOf(id.toString()))
    }

    fun updateKb(id: Long, name: String, note: String) {
        val cv = ContentValues().apply {
            put("name", name)
            put("note", note)
        }
        writableDatabase.update("kb", cv, "id=?", arrayOf(id.toString()))
    }

    fun listKbs(): List<Kb> {
        val out = ArrayList<Kb>()
        readableDatabase.rawQuery("SELECT id,name,created_at,note,top_k FROM kb ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) {
                out.add(Kb(c.getLong(0), c.getString(1), c.getLong(2), c.getString(3), c.getInt(4)))
            }
        }
        return out
    }

    /** 事务化删除知识库（chunk → doc → kb）。 */
    fun deleteKb(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("chunk", "kb_id=?", arrayOf(id.toString()))
            db.delete("doc", "kb_id=?", arrayOf(id.toString()))
            db.delete("kb", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 某附件路径是否仍被其它未删除消息引用（分支共享文件时避免误删）。 */
    fun attachmentRefCount(path: String, excludeMessageId: Long?): Int {
        // 先用 LIKE 粗筛，再解析 JSON 精确比对（避免路径子串误判）
        var count = 0
        readableDatabase.rawQuery(
            "SELECT attachments FROM message WHERE deleted=0 AND attachments IS NOT NULL AND id<>? AND attachments LIKE ?",
            arrayOf((excludeMessageId ?: -1).toString(), "%${path.replace("\\", "\\\\")}%")
        ).use { c ->
            while (c.moveToNext()) {
                val json = c.getString(0) ?: continue
                val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: continue
                for (i in 0 until arr.length()) {
                    if (arr.optJSONObject(i)?.optString("path") == path) { count++; break }
                }
            }
        }
        return count
    }

    fun listDocs(kbId: Long): List<DocItem> {
        val out = ArrayList<DocItem>()
        readableDatabase.rawQuery(
            "SELECT id,kb_id,name,pages,chars,status,created_at,src_path,pages_done,kind FROM doc WHERE kb_id=? ORDER BY id DESC",
            arrayOf(kbId.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(
                DocItem(
                    c.getLong(0), c.getLong(1), c.getString(2), c.getInt(3), c.getInt(4),
                    c.getString(5), c.getLong(6), c.getString(7), c.getInt(8), c.getString(9)
                )
            )
        }
        return out
    }

    fun addDoc(kbId: Long, name: String, kind: String = "pdf"): Long {
        val cv = ContentValues().apply {
            put("kb_id", kbId)
            put("name", name)
            put("pages", 0)
            put("chars", 0)
            put("status", "parsing")
            put("created_at", System.currentTimeMillis())
            put("kind", kind)
        }
        return writableDatabase.insert("doc", null, cv)
    }

    fun updateDoc(id: Long, pages: Int, chars: Int, status: String) {
        val cv = ContentValues().apply {
            put("pages", pages)
            put("chars", chars)
            put("status", status)
        }
        writableDatabase.update("doc", cv, "id=?", arrayOf(id.toString()))
    }

    fun setDocStatus(id: Long, status: String) {
        val cv = ContentValues().apply { put("status", status) }
        writableDatabase.update("doc", cv, "id=?", arrayOf(id.toString()))
    }

    fun deleteDocRow(id: Long) {
        runCatching { writableDatabase.delete("doc", "id=?", arrayOf(id.toString())) }
    }

    fun deleteDoc(id: Long) {
        writableDatabase.delete("chunk", "doc_id=?", arrayOf(id.toString()))
        writableDatabase.delete("doc", "id=?", arrayOf(id.toString()))
    }

    fun listCloudModels(type: String? = null): List<CloudModel> {
        val out = ArrayList<CloudModel>()
        val sql = if (type == null)
            "SELECT id,type,name,base_url,api_key,model,notes FROM cloud_model ORDER BY id DESC"
        else
            "SELECT id,type,name,base_url,api_key,model,notes FROM cloud_model WHERE type=? ORDER BY id DESC"
        val args = if (type == null) null else arrayOf(type)
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out.add(CloudModel(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6)))
            }
        }
        return out
    }

    fun cloudModelById(id: Long): CloudModel? = listCloudModels().firstOrNull { it.id == id }

    fun addCloudModel(type: String, name: String, baseUrl: String, apiKey: String, model: String, notes: String?): Long {
        val cv = ContentValues().apply {
            put("type", type); put("name", name); put("base_url", baseUrl)
            put("api_key", apiKey); put("model", model); put("notes", notes)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("cloud_model", null, cv)
    }

    fun updateCloudModel(id: Long, name: String, baseUrl: String, apiKey: String, model: String, notes: String?) {
        val cv = ContentValues().apply {
            put("name", name); put("base_url", baseUrl); put("api_key", apiKey); put("model", model); put("notes", notes)
        }
        writableDatabase.update("cloud_model", cv, "id=?", arrayOf(id.toString()))
    }

    fun deleteCloudModel(id: Long) {
        writableDatabase.delete("cloud_model", "id=?", arrayOf(id.toString()))
    }

    fun listSources(): List<ModelSource> {
        val out = ArrayList<ModelSource>()
        readableDatabase.rawQuery("SELECT id,name,url,builtin FROM model_source ORDER BY builtin DESC, id ASC", null).use { c ->
            while (c.moveToNext()) out.add(ModelSource(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) == 1))
        }
        return out
    }

    fun addSource(name: String, url: String, builtin: Boolean = false): Long {
        val cv = ContentValues().apply {
            put("name", name); put("url", url); put("builtin", if (builtin) 1 else 0)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("model_source", null, cv)
    }

    fun updateSource(id: Long, name: String, url: String) {
        val cv = ContentValues().apply { put("name", name); put("url", url) }
        writableDatabase.update("model_source", cv, "id=?", arrayOf(id.toString()))
    }

    fun deleteSource(id: Long) {
        writableDatabase.delete("model_source", "id=?", arrayOf(id.toString()))
    }

    fun addChunks(kbId: Long, docId: Long, chunks: List<Triple<Int, String, FloatArray>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((page, text, emb) in chunks) {
                val cv = ContentValues().apply {
                    put("kb_id", kbId)
                    put("doc_id", docId)
                    put("page", page)
                    put("text", text)
                    put("emb", floatsToBytes(emb))
                }
                db.insert("chunk", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun chunksOfKb(kbId: Long): List<StoredChunk> {
        val out = ArrayList<StoredChunk>()
        readableDatabase.rawQuery(
            "SELECT c.id,c.doc_id,d.name,c.page,c.text,c.emb FROM chunk c JOIN doc d ON d.id=c.doc_id WHERE c.kb_id=?",
            arrayOf(kbId.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    StoredChunk(
                        c.getLong(0), c.getLong(1), c.getString(2), c.getInt(3),
                        c.getString(4), bytesToFloats(c.getBlob(5))
                    )
                )
            }
        }
        return out
    }

    fun chunkCount(kbId: Long): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM chunk WHERE kb_id=?", arrayOf(kbId.toString())).use { c ->
            if (c.moveToFirst()) return c.getInt(0)
        }
        return 0
    }

    fun getSetting(key: String): String? {
        readableDatabase.rawQuery("SELECT v FROM setting WHERE k=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    fun setSetting(key: String, value: String) {
        val cv = ContentValues().apply {
            put("k", key)
            put("v", value)
        }
        writableDatabase.insertWithOnConflict("setting", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
