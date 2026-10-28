package io.dsh.localkb

import android.content.Context
import kotlin.math.sqrt

/**
 * 长记忆：把每轮问答写成一个可检索片段（问题 + 回答），下次提问时召回语义相关的历史轮次。
 *
 * 向量来源两级：
 *  1. 优先用应用内的 bge-small（ONNX）
 *  2. 模型不可用（未下载/加载失败）时退化为“字符二元组哈希向量”，保证功能可用
 */
object LongMemory {

    const val TOP_K = 4
    const val MIN_SCORE = 0.55f
    /** 哈希兜底向量的相似度分布偏低，单独设阈值。 */
    const val HASH_MIN_SCORE = 0.30f
    /** 最近 N 条消息不参与召回，避免和上下文窗口重复。 */
    const val EXCLUDE_RECENT_MSGS = 6

    data class Hit(val messageId: Long, val text: String, val score: Float)

    // ---------- 向量 ----------

    private const val HASH_DIM = 256

    /** 字符二元组哈希向量（L2 归一化），中英文都能用，无需模型。 */
    fun hashedEmbedding(text: String): FloatArray {
        val v = FloatArray(HASH_DIM)
        val t = text.lowercase().replace(Regex("""\s+"""), " ").trim()
        if (t.isEmpty()) return v
        // 单字权重小、二元组权重大：中文里二元组更能区分主题
        for (ch in t) {
            if (ch.isWhitespace()) continue
            v[((ch.code * 7) and 0x7fffffff) % HASH_DIM] += 0.5f
        }
        for (i in 0 until t.length - 1) {
            val h = (t[i].code * 31 + t[i + 1].code) and 0x7fffffff
            v[h % HASH_DIM] += 1f
        }
        return normalize(v)
    }

    /** 向量 + 来源标记（ONNX 与哈希向量的相似度分布不同，阈值要分开）。 */
    data class Vec(val v: FloatArray, val onnx: Boolean)

    fun embed(context: Context, text: String): Vec {
        val onnx = runCatching { EmbedderHolder.get(context).embed(text) }.getOrNull()
        return if (onnx != null) Vec(onnx, true) else Vec(hashedEmbedding(text), false)
    }

    fun normalize(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        val len = sqrt(n)
        if (len < 1e-9f) return v
        for (i in v.indices) v[i] = v[i] / len
        return v
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    // ---------- 写入 ----------

    /** 一轮问答结束后写入记忆（失败静默）。 */
    fun remember(
        context: Context,
        db: Db,
        chatId: Long,
        messageId: Long,
        userText: String,
        reply: String,
    ) {
        runCatching {
            val text = "问：$userText\n答：$reply"
            val emb = embed(context, userText).v
            db.addTurnMemory(chatId, messageId, text, emb)
        }
    }

    // ---------- 召回 ----------

    fun recall(context: Context, db: Db, chatId: Long, query: String): List<Hit> {
        val rows = runCatching { db.listTurnMemories(chatId, 600) }.getOrDefault(emptyList())
        if (rows.isEmpty()) return emptyList()
        val q = embed(context, query)
        val qv = q.v
        val minScore = if (q.onnx) MIN_SCORE else HASH_MIN_SCORE
        val recentIds = runCatching { db.recentMessageIds(chatId, EXCLUDE_RECENT_MSGS) }.getOrDefault(emptySet())
        return rows.asSequence()
            .filter { it.messageId !in recentIds }
            .map { Hit(it.messageId, it.text, cosine(qv, it.emb)) }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(TOP_K)
            .toList()
    }

    /** 拼成注入用的文本块。 */
    fun format(hits: List<Hit>, maxChars: Int): String {
        if (hits.isEmpty()) return ""
        val sb = StringBuilder("【相关记忆（更早的对话片段，供参考，不要照抄）】\n")
        for (h in hits) {
            val compact = h.text.replace(Regex("\\s+"), " ").trim()
            val piece = "- " + if (compact.length > 240) compact.take(239) + "…" else compact
            if (sb.length + piece.length > maxChars) break
            sb.append(piece).append('\n')
        }
        return if (sb.length <= 40) "" else sb.toString().trim()
    }
}
