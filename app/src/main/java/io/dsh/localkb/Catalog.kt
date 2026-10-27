package io.dsh.localkb

import android.content.Context
import java.io.File

/**
 * 本地模型目录（下载源内置清单）。
 * 全部为手机可跑的小模型；对话/识图走 GGUF（llama.cpp），向量走内置 ONNX。
 */
object Catalog {

    const val TYPE_CHAT = "chat"
    const val TYPE_VISION = "vision"
    const val TYPE_EMBED = "embed"

    data class Model(
        val id: String,
        val type: String,
        val name: String,
        val desc: String,
        val sizeLabel: String,
        val fileName: String,
        val mmprojName: String? = null,
        val mmprojSizeLabel: String? = null,
        val url: String,
        val mmprojUrl: String? = null,
        val iconRes: Int,
        val source: String,
        val builtin: Boolean = false,
        val note: String? = null,
        val sizeBytes: Long = 0L,
        val sha256: String? = null,
        val mmprojSizeBytes: Long = 0L,
        val mmprojSha256: String? = null,
    )

    private const val MS = "https://modelscope.cn/api/v1/models"
    private fun ms(repo: String, file: String) = "$MS/$repo/repo?Revision=master&FilePath=$file"

    val MODELS: List<Model> = listOf(
        Model(
            id = "qwen2.5-1.5b",
            type = TYPE_CHAT,
            name = "Qwen2.5-1.5B-Instruct",
            desc = "Qwen2.5 1.5B 是阿里通义的中文小模型，问答稳定、响应较快，适合日常对话。",
            sizeLabel = "1.0 GB",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            url = ms("Qwen/Qwen2.5-1.5B-Instruct-GGUF", "qwen2.5-1.5b-instruct-q4_k_m.gguf"),
            iconRes = R.drawable.model_qwen,
            source = "ModelScope · Qwen",
            sizeBytes = 1117320736L,
            sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
        ),
        Model(
            id = "qwen3-1.7b",
            type = TYPE_CHAT,
            name = "Qwen3-1.7B",
            desc = "Qwen3 1.7B 是通义新一代小模型，支持先思考再回答，中文与推理更强。",
            sizeLabel = "1.1 GB",
            fileName = "Qwen3-1.7B-Q4_K_M.gguf",
            url = ms("ggml-org/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q4_K_M.gguf"),
            iconRes = R.drawable.model_qwen,
            source = "ModelScope · ggml-org",
            sizeBytes = 1282439264L,
            sha256 = "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5",
        ),
        Model(
            id = "qwen3-0.6b",
            type = TYPE_CHAT,
            name = "Qwen3-0.6B",
            desc = "Qwen3 0.6B 是最轻的一档，占用小、出字快，适合低配机或快速摘要。",
            sizeLabel = "0.43 GB",
            fileName = "Qwen3-0.6B-Q4_0.gguf",
            url = ms("ggml-org/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q4_0.gguf"),
            iconRes = R.drawable.model_qwen,
            source = "ModelScope · ggml-org",
            sizeBytes = 428970080L,
            sha256 = "da2572f16c06133561ce56accaa822216f2391ef4d37fba427801cd6736417d4",
        ),
        Model(
            id = "glm-edge-1.5b",
            type = TYPE_CHAT,
            name = "GLM-Edge-1.5B-Chat",
            desc = "智谱端侧模型 GLM-Edge 1.5B，为手机端优化，中文问答扎实。",
            sizeLabel = "0.98 GB",
            fileName = "glm-edge-1.5b-chat-Q4_K_M.gguf",
            url = ms("ZhipuAI/glm-edge-1.5b-chat-GGUF", "ggml-model-Q4_K_M.gguf"),
            iconRes = R.drawable.model_glm,
            source = "ModelScope · ZhipuAI",
            sizeBytes = 980470144L,
            sha256 = "c78e1b978b44cd4d88d4bdc81ada1ca26b0089c502704c4e527ff6dc11e08f75",
        ),
        Model(
            id = "deepseek-r1-1.5b",
            type = TYPE_CHAT,
            name = "DeepSeek-R1-Distill-Qwen-1.5B",
            desc = "DeepSeek R1 蒸馏版 1.5B，会先推理再作答，数学与逻辑更强，速度偏慢。",
            sizeLabel = "1.1 GB",
            fileName = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            url = ms("unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF", "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"),
            iconRes = R.drawable.model_deepseek,
            source = "ModelScope · unsloth",
            sizeBytes = 1117321312L,
            sha256 = "f3bdf9cf31dee4b57ae4e455a1cb0d01b5c2c1b50d72d3112141c195506c2840",
        ),
        Model(
            id = "gemma-3-1b",
            type = TYPE_CHAT,
            name = "Gemma-3-1B-it",
            desc = "Gemma 3 1B 是谷歌的轻量模型，体积最小、速度最快，中文一般但够用。",
            sizeLabel = "0.8 GB",
            fileName = "gemma-3-1b-it-Q4_K_M.gguf",
            url = ms("ggml-org/gemma-3-1b-it-GGUF", "gemma-3-1b-it-Q4_K_M.gguf"),
            iconRes = R.drawable.model_gemma,
            source = "ModelScope · ggml-org",
            sizeBytes = 806058240L,
            sha256 = "8ccc5cd1f1b3602548715ae25a66ed73fd5dc68a210412eea643eb20eb75a135",
        ),
        Model(
            id = "qwen2vl-2b",
            type = TYPE_VISION,
            name = "Qwen2-VL-2B-Instruct",
            desc = "Qwen2-VL 2B 支持看图识字与图片问答，离线可用，识一张约 10–15 秒。",
            sizeLabel = "1.7 GB",
            fileName = "Qwen2-VL-2B-Instruct-Q4_K_M.gguf",
            mmprojName = "mmproj-Qwen2-VL-2B-Instruct-Q8_0.gguf",
            mmprojSizeLabel = "0.71 GB",
            url = ms("ggml-org/Qwen2-VL-2B-Instruct-GGUF", "Qwen2-VL-2B-Instruct-Q4_K_M.gguf"),
            mmprojUrl = ms("ggml-org/Qwen2-VL-2B-Instruct-GGUF", "mmproj-Qwen2-VL-2B-Instruct-Q8_0.gguf"),
            iconRes = R.drawable.model_qwen,
            source = "ModelScope · ggml-org",
            sizeBytes = 986046944L,
            sha256 = "5745685d2e607a82a0696c1118e56a2a1ae0901da450fd9cd4f161c6b62867d7",
            mmprojSizeBytes = 709883360L,
            mmprojSha256 = "a0ad91f00a7a80dcf84d719a61b00ee2e07b71794f4ee2dfa81a254621a8c418",
        ),
        Model(
            id = "bge-small-zh",
            type = TYPE_EMBED,
            name = "bge-small-zh-v1.5",
            desc = "BGE 中文小向量模型，随应用内置，用于知识库检索，无需下载。",
            sizeLabel = "24 MB（内置）",
            fileName = "",
            url = "",
            iconRes = R.drawable.model_bge,
            source = "内置",
            builtin = true,
        ),
    )

    fun byType(type: String): List<Model> = MODELS.filter { it.type == type }

    fun byId(id: String?): Model? = id?.let { mid -> MODELS.firstOrNull { it.id == mid } }

    /** 已安装判定：对话/识图看文件是否存在；内置向量恒为已装。 */
    fun isInstalled(context: Context, m: Model): Boolean {
        if (m.builtin) return true
        val dir = LocalModels.dir(context)
        val main = File(dir, m.fileName)
        if (!main.isFile) return false
        if (m.sizeBytes > 0) { if (main.length() != m.sizeBytes) return false }
        else if (main.length() < 200L * 1024 * 1024) return false
        val proj = m.mmprojName?.let { File(dir, it) }
        if (proj != null) {
            if (!proj.isFile) return false
            if (m.mmprojSizeBytes > 0) { if (proj.length() != m.mmprojSizeBytes) return false }
            else if (proj.length() < 300L * 1024 * 1024) return false
        }
        return true
    }

    fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun file(context: Context, m: Model): File = File(LocalModels.dir(context), m.fileName)

    fun mmprojFile(context: Context, m: Model): File? = m.mmprojName?.let { File(LocalModels.dir(context), it) }

    fun delete(context: Context, m: Model): Boolean {
        if (m.builtin) return false
        LocalLlm.unload()
        var ok = runCatching { file(context, m).delete() }.getOrDefault(false)
        mmprojFile(context, m)?.let { ok = runCatching { it.delete() }.getOrDefault(false) || ok }
        return ok
    }

    /** 下载（主模型 + 可选 mmproj），进度回调为 0..1。 */
    fun download(
        context: Context,
        m: Model,
        onProgress: (Float, String) -> Unit,
    ): Result<Unit> = runCatching {
        val total = 1f
        val mainSpec = LocalModels.Spec(
            id = m.id, name = m.name, fileName = m.fileName,
            sizeLabel = m.sizeLabel, note = m.desc, url = m.url,
        )
        LocalModels.download(context, mainSpec) { done, all ->
            val p = if (all > 0) done.toFloat() / all else 0f
            onProgress(p * if (m.mmprojName != null) 0.58f else 1f, "主模型 ${done / 1048576}/${all / 1048576} MB")
        }.getOrThrow()

        if (m.mmprojName != null && m.mmprojUrl != null) {
            val projSpec = LocalModels.Spec(
                id = m.id + "-mmproj", name = m.name + " 视觉编码器", fileName = m.mmprojName,
                sizeLabel = m.mmprojSizeLabel ?: "", note = "", url = m.mmprojUrl,
            )
            LocalModels.download(context, projSpec) { done, all ->
                val p = if (all > 0) done.toFloat() / all else 0f
                onProgress(0.58f + p * 0.42f, "视觉编码器 ${done / 1048576}/${all / 1048576} MB")
            }.getOrThrow()
        }
        // P1-5：字节数 + SHA-256 校验，通过才认为下载成功
        val mainFile = file(context, m)
        if (m.sizeBytes > 0 && mainFile.length() != m.sizeBytes) {
            mainFile.delete()
            error("主模型大小不符：${mainFile.length()} / ${m.sizeBytes}")
        }
        if (!m.sha256.isNullOrBlank()) {
            val actual = sha256(mainFile)
            if (!actual.equals(m.sha256, ignoreCase = true)) {
                mainFile.delete()
                error("主模型校验失败：$actual")
            }
        }
        m.mmprojName?.let { name ->
            val f = File(LocalModels.dir(context), name)
            if (m.mmprojSizeBytes > 0 && f.length() != m.mmprojSizeBytes) {
                f.delete(); error("视觉编码器大小不符")
            }
            if (!m.mmprojSha256.isNullOrBlank()) {
                val actual = sha256(f)
                if (!actual.equals(m.mmprojSha256, ignoreCase = true)) {
                    f.delete(); error("视觉编码器校验失败：$actual")
                }
            }
        }
        onProgress(1f, "完成")
    }
}
