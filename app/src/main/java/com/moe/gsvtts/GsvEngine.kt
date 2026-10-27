package com.moe.gsvtts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.util.Properties
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.min

/**
 * GPT-SoVITS 端侧推理引擎（Genie ONNX 格式，v2 / v2ProPlus）。
 *
 * 与 PC 端 `genie-tts` 的推理链路逐行对齐（见 tools/sim_device.py）：
 * ```
 * 中文文本 -> TextFrontend -> phones
 * encoder(ref_seq, text_seq, ref_bert(0), text_bert(0), ssl) -> x, prompts
 * first_stage_decoder(x, prompts) -> y, y_emb, 24x(k,v)
 * stage_decoder 循环 -> semantic tokens
 * vits(text_seq, pred_semantic, ref_audio|ge) -> 32kHz float 波形
 * ```
 * 参考特征（HuBERT / 说话人向量 / 参考音频）在 PC 端离线算好，端上不再需要
 * hubert.onnx / speaker_encoder.onnx / RoBERTa，省下约 1GB。
 */
class GsvEngine(
    private val voiceDir: File?,
    private val textAssetDir: File,
) {
    companion object {
        const val TAG = "GsvEngine"
        const val SAMPLE_RATE = 32000
        private const val N_LAYERS = 24
        private const val MAX_STEPS = 500
        private const val MAX_CHARS = 40
    }

    @Volatile
    var loaded = false
        private set

    private var env: OrtEnvironment? = null
    private var opts: OrtSession.SessionOptions? = null

    private var encoder: OrtSession? = null
    private var firstStage: OrtSession? = null
    private var stageDecoder: OrtSession? = null
    private var vits: OrtSession? = null
    private var frontend: TextFrontend? = null

    private var isV2pp = false
    private var refSeq: LongArray = LongArray(0)
    private var refBert: FloatArray = FloatArray(0)
    private var ssl: FloatArray = FloatArray(0)
    private var sslLen = 0
    private var refAudio: FloatArray? = null
    private var ge: FloatArray? = null
    private var geAdv: FloatArray? = null
    private var refLen = 0

    /** 最近一次合成的耗时分解，便于调试。 */
    var lastStats: String = ""
        private set

    fun load(): Boolean {
        try {
        val dir = voiceDir ?: return false
        if (!dir.isDirectory) return false
        val meta = readMeta(File(dir, "meta.properties"))
        isV2pp = meta["type"] == "v2pp"

        val e = OrtEnvironment.getEnvironment()
        val defaultThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val threads = System.getProperty("koe.threads", defaultThreads.toString()).toIntOrNull() ?: defaultThreads
        val ep = System.getProperty("koe.ep", "cpu")
        val o = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            if (ep == "xnnpack") {
                try {
                    addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                } catch (t: Throwable) {
                    // 这个 ORT 构建没带 XNNPACK，忽略即可
                    println("XNNPACK unavailable: ${t.message}")
                }
            }
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // 量化模型加载时 ORT 会刷大量 shape-merge 警告，这里静音
            setSessionLogLevel(ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
        }
        env = e
        opts = o
        encoder = e.createSession(File(dir, "t2s_encoder_fp32.onnx").absolutePath, o)
        firstStage = e.createSession(File(dir, "t2s_first_stage_decoder_fp32.onnx").absolutePath, o)
        stageDecoder = e.createSession(File(dir, "t2s_stage_decoder_fp32.onnx").absolutePath, o)
        vits = e.createSession(File(dir, "vits_fp32.onnx").absolutePath, o)

        frontend = TextFrontend(textAssetDir)

        refSeq = readI64(File(dir, "ref/ref_seq.i64"))
        refBert = readF32(File(dir, "ref/ref_bert.f32"))
        ssl = readF32(File(dir, "ref/ssl.f32"))
        sslLen = meta["ssl_len"]?.toIntOrNull() ?: (ssl.size / 768)
        if (isV2pp) {
            ge = readF32(File(dir, "ref/ge.f32"))
            geAdv = readF32(File(dir, "ref/ge_adv.f32"))
        } else {
            refAudio = readF32(File(dir, "ref/ref_audio.f32"))
        }
        refLen = refSeq.size.toInt()
        loaded = frontend?.loaded == true
        println("$TAG: loaded=$loaded refLen=$refLen sslLen=$sslLen v2pp=$isV2pp")
        return loaded
            } catch (t: Throwable) {
            println("GsvEngine load failed: ${t.message}")
            loaded = false
            close()
            return false
        }
    }

    fun synthesize(text: String, speed: Int): ShortArray {
        if (!loaded) return ShortArray(0)
        val t0 = System.currentTimeMillis()
        val out = ArrayList<ShortArray>()
        var totalSamples = 0
        for (seg in splitSentences(TextNormalizer.normalize(text))) {
            val pcm = synthesizeOne(seg)
            if (pcm.isNotEmpty()) {
                out.add(pcm)
                totalSamples += pcm.size
            }
        }
        val all = ShortArray(totalSamples)
        var off = 0
        for (a in out) {
            System.arraycopy(a, 0, all, off, a.size)
            off += a.size
        }
        val ms = System.currentTimeMillis() - t0
        lastStats = "chars=${text.length} samples=$totalSamples ms=$ms"
        println("$TAG: $lastStats")
        return applySpeed(all, speed)
    }

    private fun synthesizeOne(text: String): ShortArray {
        val fe = frontend ?: return ShortArray(0)
        val g2p = fe.g2p("." + text)
        val phones = g2p.phones
        if (phones.isEmpty()) return ShortArray(0)

        val textSeq = LongArray(phones.size) { phones[it].toLong() }
        val textBert = FloatArray(phones.size * 1024)
        val env = env!!

        val refSeqT = tensor1xN(refSeq, longArrayOf(1, refLen.toLong()))
        val textSeqT = tensor1xN(textSeq, longArrayOf(1, textSeq.size.toLong()))
        val refBertT = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(refBert), longArrayOf(refLen.toLong(), 1024)
        )
        val textBertT = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(textBert), longArrayOf(textSeq.size.toLong(), 1024)
        )
        val sslT = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(ssl), longArrayOf(1, 768, sslLen.toLong())
        )

        val encOut = encoder!!.run(
            mapOf(
                "ref_seq" to refSeqT, "text_seq" to textSeqT,
                "ref_bert" to refBertT, "text_bert" to textBertT, "ssl_content" to sslT
            )
        )
        var x = encOut.get("x").orElseThrow() as OnnxTensor
        var prompts = encOut.get("prompts").orElseThrow() as OnnxTensor

        var res = firstStage!!.run(mapOf("x" to x, "prompts" to prompts))
        var y = res.get("y").orElseThrow() as OnnxTensor
        var yEmb = res.get("y_emb").orElseThrow() as OnnxTensor
        val kv = arrayOfNulls<OnnxTensor>(N_LAYERS * 2)
        for (l in 0 until N_LAYERS) {
            kv[l * 2] = res.get("present_k_layer_$l").orElseThrow() as OnnxTensor
            kv[l * 2 + 1] = res.get("present_v_layer_$l").orElseThrow() as OnnxTensor
        }
        var prev: OrtSession.Result? = res
        var steps = 0
        var stop = false
        while (steps < MAX_STEPS && !stop) {
            val feed = HashMap<String, OnnxTensor>()
            feed["iy"] = y
            feed["iy_emb"] = yEmb
            for (l in 0 until N_LAYERS) {
                feed["past_k_layer_$l"] = kv[l * 2]!!
                feed["past_v_layer_$l"] = kv[l * 2 + 1]!!
            }
            res = stageDecoder!!.run(feed)
            val newY = res.get("y").orElseThrow() as OnnxTensor
            val newEmb = res.get("y_emb").orElseThrow() as OnnxTensor
            val stopT = res.get("stop_condition_tensor").orElse(null) as? OnnxTensor
            stop = when (val v = stopT?.value) {
                is Boolean -> v
                is Array<*> -> (v.firstOrNull() as? Boolean) ?: false
                else -> false
            }
            y = newY
            yEmb = newEmb
            for (l in 0 until N_LAYERS) {
                kv[l * 2] = res.get("present_k_layer_$l").orElseThrow() as OnnxTensor
                kv[l * 2 + 1] = res.get("present_v_layer_$l").orElseThrow() as OnnxTensor
            }
            prev?.close()
            prev = res
            steps++
        }
        // 取最后 steps 个 token
        val yBuf = y.longBuffer
        val yLen = yBuf.remaining()
        val take = min(steps, yLen)
        var semantic = LongArray(take)
        yBuf.position(yLen - take)
        yBuf.get(semantic)
        // 去掉 EOS（>=1024）及其之后的内容，否则 VITS 的 Gather 会越界
        val eosAt = semantic.indexOfFirst { it >= 1024 }
        if (eosAt >= 0) semantic = semantic.copyOfRange(0, eosAt)
        if (semantic.isEmpty()) {
            prev?.close()
            encOut.close()
            refSeqT.close(); textSeqT.close(); refBertT.close(); textBertT.close(); sslT.close()
            return ShortArray(0)
        }
        prev?.close()
        encOut.close()
        refSeqT.close(); textSeqT.close(); refBertT.close(); textBertT.close(); sslT.close()

        val feed = HashMap<String, OnnxTensor>()
        feed["text_seq"] = tensor1xN(textSeq, longArrayOf(1, textSeq.size.toLong()))
        feed["pred_semantic"] = OnnxTensor.createTensor(
            env, LongBuffer.wrap(semantic), longArrayOf(1, 1, semantic.size.toLong())
        )
        if (isV2pp) {
            // v2ProPlus 的 vits 期望 [1, dim, 1]
            feed["ge"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(ge!!), longArrayOf(1, ge!!.size.toLong(), 1))
            feed["ge_advanced"] = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(geAdv!!), longArrayOf(1, geAdv!!.size.toLong(), 1)
            )
        } else {
            val ra = refAudio!!
            feed["ref_audio"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(ra), longArrayOf(1, ra.size.toLong()))
        }
        val vres = vits!!.run(feed)
        val audioT = vres.get(0) as OnnxTensor
        val fb = audioT.floatBuffer
        val n = fb.remaining()
        val f = FloatArray(n)
        fb.get(f)
        feed.values.forEach { it.close() }
        vres.close()

        val pcm = ShortArray(n)
        for (i in 0 until n) {
            var v = f[i]
            if (v > 1f) v = 1f else if (v < -1f) v = -1f
            pcm[i] = (v * 32767f).toInt().toShort()
        }
        return pcm
    }

    private fun tensor1xN(a: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(a), shape)

    private fun splitSentences(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (c in text) {
            if (c == '\n' || c == '\r') {
                if (sb.isNotBlank()) out.add(sb.toString())
                sb.setLength(0)
                continue
            }
            sb.append(c)
            if (c in "。！？!?.;；…" || sb.length >= MAX_CHARS) {
                if (sb.isNotBlank()) out.add(sb.toString().trim())
                sb.setLength(0)
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return out.filter { it.isNotBlank() }
    }

    private fun applySpeed(pcm: ShortArray, speed: Int): ShortArray {
        if (speed in 95..105 || pcm.isEmpty()) return pcm
        val factor = (100.0 / speed.coerceIn(50, 200))
        val outLen = (pcm.size * factor).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val src = i / factor
            val i0 = src.toInt().coerceIn(0, pcm.size - 1)
            val i1 = (i0 + 1).coerceAtMost(pcm.size - 1)
            val frac = (src - i0).toFloat()
            out[i] = (pcm[i0] * (1 - frac) + pcm[i1] * frac).toInt().toShort()
        }
        return out
    }

    fun close() {
        loaded = false
        encoder?.close(); firstStage?.close(); stageDecoder?.close(); vits?.close()
        opts?.close()
        encoder = null; firstStage = null; stageDecoder = null; vits = null
    }


    private fun readMeta(f: File): Map<String, String> {
        val props = java.util.Properties()
        if (!f.exists()) return emptyMap()
        f.inputStream().use { props.load(it) }
        return props.stringPropertyNames().associateWith { props.getProperty(it) }
    }

    private fun readF32(f: File): FloatArray {
        if (!f.exists()) return FloatArray(0)
        val bytes = f.readBytes()
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        bb.asFloatBuffer().get(out)
        return out
    }

    private fun readI64(f: File): LongArray {
        if (!f.exists()) return LongArray(0)
        val bytes = f.readBytes()
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val out = LongArray(bytes.size / 8)
        bb.asLongBuffer().get(out)
        return out
    }
}
