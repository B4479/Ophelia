package io.dsh.localkb

import kotlin.math.ln

/**
 * Hybrid retrieval: dense cosine (bge-small-zh) + BM25 over Chinese char bigrams.
 * Both legs run on-device; scores are min-max normalised and blended.
 */
object Hybrid {

    private class CacheEntry(val chunks: List<StoredChunk>, val count: Int, val stamp: Long)

    @Volatile
    private var cache: MutableMap<Long, CacheEntry> = HashMap()

    fun invalidate(kbId: Long) {
        cache.remove(kbId)
    }

    fun chunks(db: Db, kbId: Long): List<StoredChunk> {
        val count = db.chunkCount(kbId)
        cache[kbId]?.let { if (it.count == count) return it.chunks }
        val loaded = db.chunksOfKb(kbId)
        cache[kbId] = CacheEntry(loaded, loaded.size, System.currentTimeMillis())
        return loaded
    }

    /** Chinese-friendly tokens: CJK char bigrams + lowercase latin words. */
    fun tokenize(text: String): List<String> {
        val out = ArrayList<String>(text.length)
        val word = StringBuilder()
        var prevCjk: Char? = null

        fun flushWord() {
            if (word.length >= 2) out.add(word.toString().lowercase())
            word.setLength(0)
        }

        for (ch in text) {
            when {
                ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF -> {
                    flushWord()
                    val p = prevCjk
                    if (p != null) out.add("$p$ch")
                    prevCjk = ch
                }
                ch.isLetterOrDigit() -> {
                    prevCjk = null
                    word.append(ch)
                }
                else -> {
                    prevCjk = null
                    flushWord()
                }
            }
        }
        flushWord()
        return out
    }

    /** 多库统一归一化：所有库候选放在一起做 min-max，再按 0.65/0.35 融合。 */
    fun searchMulti(
        db: Db,
        kbIds: List<Long>,
        query: String,
        queryVec: FloatArray,
        topKPerKb: Map<Long, Int>,
        finalK: Int = 8,
    ): List<Hit> {
        data class Cand(val chunk: StoredChunk, val dense: Double, val bm25: Double, val kbId: Long)
        val cands = ArrayList<Cand>()
        for (kbId in kbIds) {
            val chunks = chunks(db, kbId)
            if (chunks.isEmpty()) continue
            val (bm25, dense) = score(chunks, query, queryVec)
            for (i in chunks.indices) cands.add(Cand(chunks[i], dense[i], bm25[i], kbId))
        }
        if (cands.isEmpty()) return emptyList()
        val nb = minmax(cands.map { it.bm25 }.toDoubleArray())
        val nd = minmax(cands.map { it.dense }.toDoubleArray())
        val order = (0 until cands.size).sortedByDescending { 0.65 * nd[it] + 0.35 * nb[it] }.take(finalK)
        return order.map { i ->
            val c = cands[i]
            Hit(c.chunk.docId, c.chunk.docName, c.chunk.page, c.chunk.text,
                0.65f * nd[i].toFloat() + 0.35f * nb[i].toFloat(), c.dense.toFloat())
        }
    }

    private fun minmax(a: DoubleArray): DoubleArray {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (v in a) { if (v < lo) lo = v; if (v > hi) hi = v }
        val range = hi - lo
        return if (range < 1e-9) DoubleArray(a.size) { 0.0 } else DoubleArray(a.size) { (a[it] - lo) / range }
    }

    private fun score(chunks: List<StoredChunk>, query: String, queryVec: FloatArray): Pair<DoubleArray, DoubleArray> {
        val qTerms = tokenize(query).distinct()
        val docs = chunks.map { tokenize(it.text) }
        val n = docs.size
        val nD = n.toDouble()
        val avgLen = docs.sumOf { it.size }.toDouble() / nD
        val df = HashMap<String, Int>()
        for (d in docs) for (t in qTerms) if (d.contains(t)) df[t] = (df[t] ?: 0) + 1
        val k1 = 1.2
        val b = 0.75
        val bm25 = DoubleArray(n)
        for (i in docs.indices) {
            val d = docs[i]
            if (d.isEmpty()) continue
            val len = d.size.toDouble()
            var sc = 0.0
            for (t in qTerms) {
                val f = d.count { it == t }.toDouble()
                if (f == 0.0) continue
                val idf = ln(1 + (nD - (df[t] ?: 0) + 0.5) / ((df[t] ?: 0) + 0.5))
                sc += idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * len / avgLen))
            }
            bm25[i] = sc
        }
        val dense = DoubleArray(n) { cosine(queryVec, chunks[it].emb).toDouble() }
        return bm25 to dense
    }

    fun search(db: Db, kbId: Long, query: String, queryVec: FloatArray, topK: Int = 5): List<Hit> {
        val chunks = chunks(db, kbId)
        if (chunks.isEmpty()) return emptyList()

        // --- BM25 leg ---
        val qTerms = tokenize(query).distinct()
        val docs = chunks.map { tokenize(it.text) }
        val n = docs.size
        val nD = n.toDouble()
        val avgLen = docs.sumOf { it.size }.toDouble() / nD
        val df = HashMap<String, Int>()
        for (d in docs) {
            for (t in qTerms) if (d.contains(t)) df[t] = (df[t] ?: 0) + 1
        }
        val k1 = 1.2
        val b = 0.75
        val bm25 = DoubleArray(n)
        for (i in docs.indices) {
            val d = docs[i]
            if (d.isEmpty()) continue
            val len = d.size.toDouble()
            var s = 0.0
            for (t in qTerms) {
                val f = d.count { it == t }.toDouble()
                if (f == 0.0) continue
                val idf = ln(1 + (nD - (df[t] ?: 0) + 0.5) / ((df[t] ?: 0) + 0.5))
                s += idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * len / avgLen))
            }
            bm25[i] = s
        }

        // --- dense leg ---
        val dense = DoubleArray(n) { cosine(queryVec, chunks[it].emb).toDouble() }

        fun minmax(a: DoubleArray): DoubleArray {
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            for (v in a) {
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            val range = hi - lo
            return if (range < 1e-9) DoubleArray(a.size) { 0.0 } else DoubleArray(a.size) { (a[it] - lo) / range }
        }

        val nb = minmax(bm25)
        val nd = minmax(dense)
        val out = ArrayList<Hit>(topK)
        val idx = (0 until n).sortedByDescending { 0.65 * nd[it] + 0.35 * nb[it] }.take(topK)
        for (i in idx) {
            val c = chunks[i]
            out.add(
                Hit(
                    c.docId, c.docName, c.page, c.text,
                    (0.65 * nd[i] + 0.35 * nb[i]).toFloat(),
                    dense[i].toFloat()
                )
            )
        }
        return out
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var s = 0f
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }
}
