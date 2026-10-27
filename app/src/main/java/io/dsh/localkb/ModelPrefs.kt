package io.dsh.localkb

import android.content.Context

/** 三类模型的“当前使用”选择。格式：local:<catalogId> / cloud:<cloudModelId>。 */
object ModelPrefs {

    const val KEY_CHAT = "sel_chat"
    const val KEY_VISION = "sel_vision"
    const val KEY_EMBED = "sel_embed"

    const val DEFAULT_CHAT = "local:qwen2.5-1.5b"
    const val DEFAULT_VISION = "local:qwen2vl-2b"
    const val DEFAULT_EMBED = "local:bge-small-zh"

    fun sel(db: Db, key: String, fallback: String): String =
        db.getSetting(key)?.takeIf { it.isNotBlank() } ?: fallback

    fun setSel(db: Db, key: String, value: String) = db.setSetting(key, value)

    fun chatSel(db: Db): String = sel(db, KEY_CHAT, DEFAULT_CHAT)
    fun visionSel(db: Db): String = sel(db, KEY_VISION, DEFAULT_VISION)
    fun embedSel(db: Db): String = sel(db, KEY_EMBED, DEFAULT_EMBED)

    fun isCloud(sel: String) = sel.startsWith("cloud:")

    fun cloudId(sel: String): Long? = sel.removePrefix("cloud:").toLongOrNull()

    fun localModel(sel: String): Catalog.Model? =
        if (sel.startsWith("local:")) Catalog.byId(sel.removePrefix("local:")) else null

    /** 选中的云端模型配置；若不是云端选择或记录缺失则返回 null。 */
    fun cloudModel(db: Db, sel: String): CloudModel? =
        if (isCloud(sel)) cloudId(sel)?.let { db.cloudModelById(it) } else null

    /** 把选中的云端配置同步到旧设置键（供旧代码/兼容读取）。 */
    fun syncLegacy(db: Db, chatSel: String, visionSel: String) {
        cloudModel(db, chatSel)?.let { m ->
            db.setSetting("llm_base", m.baseUrl)
            db.setSetting("llm_key", m.apiKey)
            db.setSetting("llm_model", m.model)
        }
        cloudModel(db, visionSel)?.let { m ->
            db.setSetting("vision_base", m.baseUrl)
            db.setSetting("vision_key", m.apiKey)
            db.setSetting("vision_model", m.model)
        }
    }
}
