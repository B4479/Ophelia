package io.dsh.localkb

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 端侧模型清单与下载。模型放在应用私有目录，随应用"安装"，不进 APK。
 */
object LocalModels {

    data class Spec(
        val id: String,
        val name: String,
        val fileName: String,
        val sizeLabel: String,
        val note: String,
        val url: String,
        val sizeBytes: Long = 0L,
    )

    /** 向量模型（内置在 APK 的 assets 里，只读展示）。 */
    const val EMBEDDING_NAME = "bge-small-zh-v1.5"
    const val EMBEDDING_SIZE = "24 MB"
    const val EMBEDDING_DIM = 512
    const val EMBEDDING_NOTE = "中文向量检索（知识库检索用），随 APK 内置，始终在本机运行"

    val QWEN = Spec(
        id = "qwen2.5-1.5b-instruct-q4km",
        name = "Qwen2.5-1.5B-Instruct",
        fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        sizeLabel = "1.0 GB",
        note = "中文对话，Q4_K_M 量化，纯 CPU 推理",
        url = "https://modelscope.cn/models/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/master/qwen2.5-1.5b-instruct-q4_k_m.gguf",
    )

    val VISION = Spec(
        id = "qwen2-vl-2b-instruct-q4km",
        name = "Qwen2-VL-2B-Instruct（本地识图）",
        fileName = "Qwen2-VL-2B-Instruct-Q4_K_M.gguf",
        sizeLabel = "986 MB",
        note = "端侧看图/识图，Q4_K_M 量化，纯 CPU",
        url = "https://modelscope.cn/api/v1/models/ggml-org/Qwen2-VL-2B-Instruct-GGUF/repo" +
            "?Revision=master&FilePath=Qwen2-VL-2B-Instruct-Q4_K_M.gguf",
    )

    val VISION_MMPROJ = Spec(
        id = "qwen2-vl-2b-mmproj-q8",
        name = "Qwen2-VL-2B 视觉编码器",
        fileName = "mmproj-Qwen2-VL-2B-Instruct-Q8_0.gguf",
        sizeLabel = "710 MB",
        note = "识图必须的投影器（mmproj）",
        url = "https://modelscope.cn/api/v1/models/ggml-org/Qwen2-VL-2B-Instruct-GGUF/repo" +
            "?Revision=master&FilePath=mmproj-Qwen2-VL-2B-Instruct-Q8_0.gguf",
    )

    fun chat(context: Context): Spec = QWEN

    fun dir(context: Context): File {
        val d = File(context.filesDir, "models")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun file(context: Context, spec: Spec): File = File(dir(context), spec.fileName)

    fun isInstalled(context: Context, spec: Spec): Boolean {
        val f = file(context, spec)
        return f.isFile && f.length() > 100L * 1024 * 1024
    }

    fun installedBytes(context: Context, spec: Spec): Long {
        val f = file(context, spec)
        return if (f.isFile) f.length() else 0L
    }

    fun delete(context: Context, spec: Spec): Boolean {
        LocalLlm.unload()
        return runCatching { file(context, spec).delete() }.getOrDefault(false)
    }

    /** 下载到 .part，完成后改名；支持断点续传（Range）。 */
    fun download(
        context: Context,
        spec: Spec,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> = runCatching {
        val dest = file(context, spec)
        val part = File(dest.parentFile, dest.name + ".part")

        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val start = if (part.exists()) part.length() else 0L
        val req = Request.Builder()
            .url(spec.url)
            .apply { if (start > 0) addHeader("Range", "bytes=$start-") }
            .get()
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")

            // 断点续传必须拿到 206，且 Content-Range 起点与本地长度一致；否则从头重下
            val resuming = start > 0 && resp.code == 206 &&
                (resp.header("Content-Range")?.substringAfter("bytes ")?.substringBefore("-")?.trim()?.toLongOrNull() == start)

            if (start > 0 && !resuming) {
                // 服务器忽略 Range（返回 200）或范围不符：清空临时文件，从 0 开始
                part.delete()
                if (resp.code == 206) error("服务器返回了非预期范围")
                val req2 = Request.Builder().url(spec.url).get().build()
                client.newCall(req2).execute().use { r2 ->
                    if (!r2.isSuccessful) error("HTTP ${r2.code}")
                    writeBody(r2, part, 0L, spec, onProgress)
                }
            } else {
                writeBody(resp, part, if (resuming) start else 0L, spec, onProgress)
            }
        }
        val expected = runCatching { spec.sizeLabel }.getOrNull()
        if (expected != null && spec.sizeBytes > 0 && part.length() != spec.sizeBytes) {
            error("下载长度不符：期望 ${spec.sizeBytes}，实际 ${part.length()}")
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) error("重命名失败")
        dest
    }

    /** 把响应体写入文件；已有内容时追加。 */
    private fun writeBody(
        resp: okhttp3.Response,
        part: File,
        start: Long,
        spec: Spec,
        onProgress: (Long, Long) -> Unit,
    ) {
        val body = resp.body ?: error("空响应")
        val len = body.contentLength()
        val total = if (len >= 0) start + len else -1L
        var done = start
        body.byteStream().use { input ->
            java.io.FileOutputStream(part, start > 0).use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                    onProgress(done, if (total > 0) total else done)
                }
            }
        }
    }
}
