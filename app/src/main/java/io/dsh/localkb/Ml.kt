package io.dsh.localkb

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer

/** Minimal WordPiece tokenizer compatible with bert-base-chinese style vocab (bge-*-zh). */
class BertTokenizer(private val vocab: Map<String, Int>, val maxLen: Int = 512) {

    companion object {
        const val CLS = 101L
        const val SEP = 102L
        const val UNK = 100L
        const val PAD = 0L

        fun fromAssets(context: Context, assetName: String): BertTokenizer {
            val map = HashMap<String, Int>(24000)
            context.assets.open(assetName).bufferedReader().useLines { lines ->
                var i = 0
                lines.forEach { line ->
                    map[line.trimEnd('\n', '\r')] = i
                    i++
                }
            }
            return BertTokenizer(map)
        }
    }

    private fun isCjk(c: Char): Boolean {
        val v = c.code
        return (v in 0x4E00..0x9FFF) || (v in 0x3400..0x4DBF) || (v in 0xF900..0xFAFF)
    }

    private fun isAlnum(c: Char) = c.isLetterOrDigit() && !isCjk(c)

    private fun wordPiece(word: String): List<Int> {
        if (word.isEmpty()) return emptyList()
        val lower = word.lowercase()
        val direct = vocab[lower]
        if (direct != null) return listOf(direct)
        val out = ArrayList<Int>()
        var start = 0
        while (start < lower.length) {
            var end = lower.length
            var found = -1
            while (end > start) {
                val sub = lower.substring(start, end)
                val key = if (start == 0) sub else "##$sub"
                val id = vocab[key]
                if (id != null) {
                    found = id
                    break
                }
                end--
            }
            if (found < 0) return listOf(UNK.toInt())
            out.add(found)
            start = end
        }
        return out
    }

    fun encode(text: String): LongArray {
        val ids = ArrayList<Int>(maxLen)
        val word = StringBuilder()

        fun flushWord() {
            if (word.isNotEmpty()) {
                ids.addAll(wordPiece(word.toString()))
                word.setLength(0)
            }
        }

        for (ch in text) {
            when {
                isCjk(ch) -> {
                    flushWord()
                    ids.add(vocab[ch.toString()] ?: UNK.toInt())
                }
                isAlnum(ch) -> word.append(ch)
                ch.isWhitespace() -> flushWord()
                else -> {
                    flushWord()
                    ids.add(vocab[ch.toString()] ?: UNK.toInt())
                }
            }
            if (ids.size >= maxLen - 2) break
        }
        flushWord()

        val trimmed = if (ids.size > maxLen - 2) ids.subList(0, maxLen - 2) else ids
        val out = LongArray(trimmed.size + 2)
        out[0] = CLS
        for (i in trimmed.indices) out[i + 1] = trimmed[i].toLong()
        out[out.size - 1] = SEP
        return out
    }
}

/** On-device embedding model (bge-small-zh-v1.5 quantized, ONNX). Produces L2-normalised vectors. */
class Embedder(context: Context, modelAsset: String = "bge-small-zh-v1.5.onnx") : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val tokenizer: BertTokenizer
    val dim: Int

    init {
        val opts = OrtSession.SessionOptions()
        opts.setIntraOpNumThreads(4)
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        val bytes = context.assets.open(modelAsset).readBytes()
        session = env.createSession(bytes, opts)
        tokenizer = BertTokenizer.fromAssets(context, "vocab.txt")

        // Probe dimension with a tiny input.
        val probe = runOnce(longArrayOf(BertTokenizer.CLS, BertTokenizer.SEP))
        dim = probe.size
    }

    private fun runOnce(ids: LongArray): FloatArray {
        val n = ids.size
        val mask = LongArray(n) { 1L }
        val types = LongArray(n) { 0L }

        val shape = longArrayOf(1, n.toLong())
        val inputs = HashMap<String, OnnxTensor>()
        try {
            for (name in session.inputNames) {
                val data = when (name) {
                    "input_ids" -> ids
                    "attention_mask" -> mask
                    "token_type_ids" -> types
                    else -> ids
                }
                inputs[name] = OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)
            }
            session.run(inputs).use { result ->
                val value = result.get(0).value
                return pool(value, n)
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun pool(value: Any, seqLen: Int): FloatArray {
        val vec: FloatArray = when (value) {
            is Array<*> -> {
                val first = value[0]
                when (first) {
                    is Array<*> -> { // [1][seq][dim] -> CLS
                        val seq = first as Array<FloatArray>
                        seq[0].copyOf()
                    }
                    is FloatArray -> first.copyOf() // [1][dim] already pooled
                    else -> throw IllegalStateException("unexpected output shape")
                }
            }
            is FloatArray -> value.copyOf() // flattened
            else -> throw IllegalStateException("unexpected output type: ${value::class.java}")
        }
        var sum = 0.0
        for (v in vec) sum += v.toDouble() * v
        val norm = Math.sqrt(sum).toFloat()
        if (norm > 0f) for (i in vec.indices) vec[i] = vec[i] / norm
        return vec
    }

    fun embed(text: String): FloatArray {
        val ids = tokenizer.encode(text)
        return runOnce(ids)
    }

    fun embedAll(texts: List<String>): List<FloatArray> = texts.map { embed(it) }

    override fun close() {
        session.close()
    }
}

/** Splits long text into overlapping chunks near sentence boundaries. */
object Chunker {
    fun split(text: String, target: Int = 450, overlap: Int = 80): List<String> {
        val clean = text.replace("\r", "").replace(Regex("\n{3,}"), "\n\n").trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= target) return listOf(clean)

        val out = ArrayList<String>()
        var start = 0
        while (start < clean.length) {
            var end = minOf(start + target, clean.length)
            if (end < clean.length) {
                val window = clean.substring(start, end)
                val cut = window.lastIndexOfAny(charArrayOf('。', '！', '？', '\n', '；', '.', '!', '?'))
                if (cut > target / 2) end = start + cut + 1
            }
            val piece = clean.substring(start, end).trim()
            if (piece.isNotEmpty()) out.add(piece)
            if (end >= clean.length) break
            start = maxOf(end - overlap, start + 1)
        }
        return out
    }
}
