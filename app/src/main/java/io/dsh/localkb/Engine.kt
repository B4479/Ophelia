package io.dsh.localkb

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object EmbedderHolder {
    @Volatile
    private var inst: Embedder? = null

    fun get(context: Context): Embedder {
        inst?.let { return it }
        synchronized(this) {
            inst?.let { return it }
            val e = Embedder(context.applicationContext)
            inst = e
            return e
        }
    }
}

object PdfImport {

    /** 从私有副本文件解析（后台任务用，避免依赖会失效的 SAF uri）。 */
    fun extractFile(context: Context, file: java.io.File): Result<List<Pair<Int, String>>> = runCatching {
        PDFBoxResourceLoader.init(context.applicationContext)
        val out = ArrayList<Pair<Int, String>>()
        java.io.FileInputStream(file).use { input ->
            val doc = PDDocument.load(
                input,
                MemoryUsageSetting.setupMixed(32L * 1024 * 1024).setTempDir(context.cacheDir)
            )
            try {
                val stripper = PDFTextStripper()
                val pages = doc.numberOfPages
                for (i in 1..pages) {
                    val text = try {
                        stripper.startPage = i
                        stripper.endPage = i
                        stripper.getText(doc)
                    } catch (t: Throwable) {
                        ""
                    }
                    out.add(i to text)
                }
            } finally {
                doc.close()
            }
        }
        out
    }

    /** Extract text per page. Returns list of (pageNumber, text). */
    fun extract(context: Context, uri: Uri): Result<List<Pair<Int, String>>> = runCatching {
        PDFBoxResourceLoader.init(context.applicationContext)
        val out = ArrayList<Pair<Int, String>>()
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法打开文件" }
            val doc = PDDocument.load(
                input,
                MemoryUsageSetting.setupMixed(32L * 1024 * 1024).setTempDir(context.cacheDir)
            )
            try {
                val stripper = PDFTextStripper()
                val pages = doc.numberOfPages
                for (i in 1..pages) {
                    val text = try {
                        stripper.startPage = i
                        stripper.endPage = i
                        stripper.getText(doc)
                    } catch (t: Throwable) {
                        ""
                    }
                    out.add(i to text)
                }
            } finally {
                doc.close()
            }
        }
        out
    }
}

class ImportEngine(private val context: Context, private val db: Db) {

    suspend fun importDocument(
        kbId: Long,
        uri: Uri,
        displayName: String,
        onProgress: (String) -> Unit = {}
    ): Result<Int> = withContext(Dispatchers.IO) {
        val docId = db.addDoc(kbId, displayName)
        try {
            onProgress("解析 PDF…")
            db.setDocStatus(docId, "parsing")
            val pages = PdfImport.extract(context, uri).getOrElse { e ->
                db.updateDoc(docId, 0, 0, "failed")
                return@withContext Result.failure(e)
            }

            val texts = pages.map { it.second }
            val totalChars = texts.sumOf { it.length }
            if (totalChars < 20) {
                db.updateDoc(docId, pages.size, totalChars, "empty")
                onProgress("没有可提取的文字（可能是扫描件）")
                return@withContext Result.success(0)
            }

            onProgress("切分文本…")
            val items = ArrayList<Triple<Int, String, FloatArray>>()
            val embedder = EmbedderHolder.get(context)

            var done = 0
            val totalChunks = texts.sumOf { Chunker.split(it).size }
            for ((idx, pageText) in texts.withIndex()) {
                val pageNo = pages[idx].first
                for (chunk in Chunker.split(pageText)) {
                    onProgress("向量化 $done/$totalChunks")
                    val vec = embedder.embed(chunk)
                    items.add(Triple(pageNo, chunk, vec))
                    done++
                }
                if (idx % 10 == 0) db.setDocStatus(docId, "embedding $done/$totalChunks")
            }

            onProgress("写入向量库…")
            db.addChunks(kbId, docId, items)
            db.updateDoc(docId, pages.size, totalChars, "ready")
            onProgress("完成：$done 块")
            Result.success(done)
        } catch (t: Throwable) {
            db.setDocStatus(docId, "failed")
            Result.failure(t)
        }
    }
}

object Retriever {
    fun search(db: Db, kbId: Long, queryVec: FloatArray, topK: Int = 5): List<Hit> {
        val chunks = db.chunksOfKb(kbId)
        if (chunks.isEmpty()) return emptyList()
        return chunks.map { c ->
            Hit(c.docId, c.docName, c.page, c.text, cosine(queryVec, c.emb), cosine(queryVec, c.emb))
        }.sortedByDescending { it.score }.take(topK)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var s = 0f
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }
}

class StreamCanceller {
    @Volatile
    var cancelled: Boolean = false
        private set

    @Volatile
    var call: okhttp3.Call? = null

    fun cancel() {
        cancelled = true
        runCatching { call?.cancel() }
    }
}

object Llm {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    data class Config(val baseUrl: String, val apiKey: String, val model: String)

    fun loadConfig(db: Db): Config? {
        val base = db.getSetting("llm_base") ?: return null
        val key = db.getSetting("llm_key") ?: return null
        val model = db.getSetting("llm_model") ?: return null
        if (base.isBlank() || key.isBlank() || model.isBlank()) return null
        return Config(base, key, model)
    }

    /** 通用多轮对话（聊天模式用，带人设）。 */
    fun chat(
        config: Config,
        system: String,
        history: List<Pair<String, String>>,
        user: String,
        temperature: Double = 0.85,
    ): Result<String> = runCatching {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system))
        for ((role, content) in history.takeLast(20)) {
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        messages.put(JSONObject().put("role", "user").put("content", user))

        val body = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("temperature", temperature)
            .put("stream", false)

        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(300)}")
            JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        }
    }

    /** 通用 SSE 流式请求：每收到一段文本调用 onDelta，返回完整文本。 */
    private fun stream(
        config: Config,
        messages: JSONArray,
        temperature: Double,
        onDelta: (String) -> Unit,
        canceller: StreamCanceller?,
    ): Result<String> = runCatching {
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("temperature", temperature)
            .put("stream", true)

        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(req)
        canceller?.let { it.call = call }
        android.util.Log.i("LlmStream", "request url=$url model=${config.model}")
        val sb = StringBuilder()
        call.execute().use { resp ->
            android.util.Log.i("LlmStream", "http=${resp.code} ct=${resp.header("Content-Type")}")
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                error("HTTP ${resp.code}: ${err.take(300)}")
            }
            val src = resp.body?.source() ?: error("空响应")
            try {
                while (!src.exhausted()) {
                    if (canceller?.cancelled == true) break
                    val line = src.readUtf8Line() ?: break
                    if (line.isEmpty() || !line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    if (payload == "[DONE]") break
                    val delta = runCatching {
                        JSONObject(payload).getJSONArray("choices").getJSONObject(0)
                            .optJSONObject("delta")?.optString("content").orEmpty()
                    }.getOrDefault("")
                    if (delta.isNotEmpty()) {
                        sb.append(delta)
                        onDelta(delta)
                    }
                }
            } catch (e: java.io.IOException) {
                // 用户点「停止」会 cancel 掉 Call，这里把已收到的部分当结果返回
                if (canceller?.cancelled != true) throw e
                android.util.Log.i("LlmStream", "cancelled, partial=${sb.length}")
            }
        }
        android.util.Log.i("LlmStream", "done len=${sb.length}")
        // P2-10：非取消且没有任何内容时，明确报错而不是当成“已停止生成”
        if (sb.isEmpty() && canceller?.cancelled != true) {
            error("流没有返回任何内容（可能是接口不兼容或返回了错误对象）")
        }
        sb.toString()
    }

    fun chatStream(
        config: Config,
        system: String,
        history: List<Pair<String, String>>,
        user: String,
        onDelta: (String) -> Unit,
        canceller: StreamCanceller? = null,
        temperature: Double = 0.85,
    ): Result<String> {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system))
        for ((role, content) in history.takeLast(20)) {
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        messages.put(JSONObject().put("role", "user").put("content", user))
        return stream(config, messages, temperature, onDelta, canceller)
    }

    fun answerStream(
        config: Config,
        question: String,
        hits: List<Hit>,
        onDelta: (String) -> Unit,
        canceller: StreamCanceller? = null,
    ): Result<String> {
        val ctx = StringBuilder()
        hits.forEachIndexed { i, h ->
            ctx.append("[").append(i + 1).append("]《").append(h.docName).append("》第")
                .append(h.page).append("页\n").append(h.text).append("\n\n")
        }
        val sys = "你是一个严谨的知识库助手。只能依据给定的资料片段回答；" +
                "如果资料不足，直接说“资料中没有提到”。回答末尾用 [1][2] 标注引用的片段。"
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", sys))
        messages.put(
            JSONObject().put("role", "user")
                .put("content", "资料片段：\n" + ctx + "\n问题：" + question)
        )
        return stream(config, messages, 0.2, onDelta, canceller)
    }

    fun answerStreamCustom(
        config: Config,
        system: String,
        question: String,
        hits: List<Hit>,
        onDelta: (String) -> Unit,
        canceller: StreamCanceller? = null,
    ): Result<String> {
        val ctx = StringBuilder()
        hits.forEachIndexed { i, h ->
            ctx.append("[").append(i + 1).append("]《").append(h.docName).append("》第")
                .append(h.page).append("页\n").append(h.text).append("\n\n")
        }
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system))
        messages.put(
            JSONObject().put("role", "user")
                .put("content", "资料片段：\n" + ctx + "\n问题：" + question)
        )
        return stream(config, messages, 0.2, onDelta, canceller)
    }

    fun answer(config: Config, question: String, hits: List<Hit>): Result<String> = runCatching {
        val ctx = StringBuilder()
        hits.forEachIndexed { i, h ->
            ctx.append("[${i + 1}]《${h.docName}》第${h.page}页\n${h.text}\n\n")
        }
        val sys = "你是一个严谨的知识库助手。只能依据给定的资料片段回答；" +
                "如果资料不足，直接说“资料中没有提到”。回答末尾用 [1][2] 标注引用的片段。"

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", sys))
        messages.put(
            JSONObject().put("role", "user")
                .put("content", "资料片段：\n$ctx\n问题：$question")
        )
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("temperature", 0.2)
            .put("stream", false)

        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(300)}")
            val json = JSONObject(text)
            json.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        }
    }
}
