package io.dsh.localkb

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 云端识图：OpenAI 兼容的多模态接口。 */
object Vision {

    data class Config(val baseUrl: String, val apiKey: String, val model: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    const val DEFAULT_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    const val DEFAULT_MODEL = "qwen3-vl-plus"

    fun loadConfig(db: Db): Config? {
        val key = db.getSetting("vision_key")?.takeIf { it.isNotBlank() } ?: return null
        val base = db.getSetting("vision_base")?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE
        val model = db.getSetting("vision_model")?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
        return Config(base.trimEnd('/'), key, model)
    }

    /** 把图片 base64 发给视觉模型，返回文字描述/识别结果。 */
    fun describe(
        config: Config,
        imageBase64: String,
        mime: String = "image/jpeg",
        prompt: String = "请用中文描述这张图片的内容；如果图中有文字，请完整提取出来。",
    ): Result<String> = runCatching {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:$mime;base64,$imageBase64"))
            )
        val body = JSONObject()
            .put("model", config.model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("max_tokens", 800)

        val req = Request.Builder()
            .url(config.baseUrl + "/chat/completions")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${text.take(200)}")
            JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content")
        }
    }
}
