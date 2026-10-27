package io.dsh.localkb

/**
 * 一次性把已有的云端配置“搬进”模型中心。
 * 只读取设置里已存在的 key（llm_* / vision_*），不额外写死任何密钥。
 */
object CloudPresets {

    private const val SEEDED = "cloud_seeded_v2"

    fun seedIfNeeded(db: Db) {
        if (db.getSetting(SEEDED) == "1") return

        val llmBase = db.getSetting("llm_base").orEmpty().trimEnd('/')
        val llmKey = db.getSetting("llm_key").orEmpty()
        val llmModel = db.getSetting("llm_model").orEmpty()

        val vBase = (db.getSetting("vision_base")?.takeIf { it.isNotBlank() }
            ?: Vision.DEFAULT_BASE).trimEnd('/')
        val vKey = db.getSetting("vision_key").orEmpty()

        val existing = db.listCloudModels().map { Triple(it.type, it.baseUrl, it.model) }.toSet()

        fun add(type: String, name: String, base: String, key: String, model: String, notes: String) {
            if (base.isBlank() || key.isBlank() || model.isBlank()) return
            if (Triple(type, base, model) in existing) return
            db.addCloudModel(type, name, base, key, model, notes)
        }

        // 1) 官方 DeepSeek（对话）
        if (llmBase.isNotBlank() && llmKey.isNotBlank() && llmModel.isNotBlank()) {
            val isOfficial = llmBase.contains("api.deepseek.com")
            add(
                Catalog.TYPE_CHAT,
                if (isOfficial) "DeepSeek 官方" else "云端对话（原配置）",
                llmBase, llmKey, llmModel,
                if (isOfficial) "DeepSeek 官方接口，稳定、便宜" else "从原「云端模型」迁移"
            )
        }

        // 2) 网关上的对话模型（用识图那套 key，同一个网关）
        if (vBase.isNotBlank() && vKey.isNotBlank()) {
            add(Catalog.TYPE_CHAT, "DeepSeek V4.1 Flash", vBase, vKey, "deepseek-v4.1-flash", "网关版 DeepSeek，速度快、价格低")
            add(Catalog.TYPE_CHAT, "GLM-5.3", vBase, vKey, "glm-5.3", "智谱 GLM 最新旗舰，中文强")
            add(Catalog.TYPE_CHAT, "Kimi K3.1 Pro", vBase, vKey, "kimi/kimi-k3.1-pro", "月之暗面 Kimi，长文与写作强")
            add(Catalog.TYPE_CHAT, "Qwen3.7 Flash", vBase, vKey, "qwen3.7-flash", "通义 Qwen3.7 轻量版，响应快")

            // 3) 识图模型
            add(Catalog.TYPE_VISION, "Qwen3-VL-Plus（识图）", vBase, vKey, "qwen3-vl-plus", "通用识图：看图问答、界面理解")
            add(Catalog.TYPE_VISION, "Qwen-VL-OCR（识字）", vBase, vKey, "qwen-vl-ocr", "专攻文字提取，截图/扫描件更准")
        }

        db.setSetting(SEEDED, "1")
    }
}
