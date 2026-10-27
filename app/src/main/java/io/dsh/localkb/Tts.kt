package io.dsh.localkb

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import com.moe.gsvtts.GsvEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.util.zip.ZipInputStream

/** 声音包（一个目录 = 一种音色 / 一种语气）。 */
data class TtsVoice(val id: String, val name: String, val dir: File)

object TtsAssets {
    private val FILES = listOf("charmap.txt", "p2s.txt", "phrases.txt", "poly.txt", "syl.txt", "symbols.txt")

    @Volatile
    private var cached: File? = null

    fun ensure(context: Context): File {
        cached?.let { if (File(it, "symbols.txt").exists()) return it }
        val dir = File(context.filesDir, "tts")
        if (!dir.exists()) dir.mkdirs()
        for (n in FILES) {
            val out = File(dir, n)
            if (out.exists() && out.length() > 0) continue
            context.assets.open("tts/$n").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
        }
        cached = dir
        return dir
    }
}

object TtsVoices {

    fun voicesRoot(context: Context): File {
        val dir = File(context.filesDir, "voices")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 只接受真正可读的完整声音包（共享目录里的包对我们是不可读的，要过滤掉）。 */
    private fun isUsable(dir: File): Boolean {
        val meta = File(dir, "meta.properties")
        val vits = File(dir, "vits_fp32.onnx")
        return meta.isFile && meta.canRead() && meta.length() > 0 &&
            vits.isFile && vits.canRead() && vits.length() > 0
    }

    fun list(context: Context): List<TtsVoice> {
        val roots = mutableListOf(voicesRoot(context))
        runCatching {
            File("/sdcard/Android/media/com.moe.gsvtts/voices").let { if (it.isDirectory) roots.add(it) }
        }
        runCatching {
            File("/sdcard/LocalKB/voices").let { if (it.isDirectory) roots.add(it) }
        }

        val out = ArrayList<TtsVoice>()
        for (root in roots) {
            val dirs = runCatching { root.listFiles { f -> f.isDirectory } }.getOrNull() ?: continue
            for (d in dirs) {
                if (!isUsable(d)) continue
                val name = runCatching {
                    File(d, "meta.properties").readLines()
                        .firstOrNull { it.startsWith("name=") }?.substringAfter("=")?.trim()
                }.getOrNull()?.takeIf { it.isNotEmpty() } ?: d.name
                if (out.none { it.dir.absolutePath == d.absolutePath }) {
                    out.add(TtsVoice(d.name, name, d))
                }
            }
        }
        return out.sortedBy { it.name }
    }

    /** 从 zip 导入一个声音包（压缩包内任意层级含 meta.properties 即可）。 */
    fun importZip(context: Context, uri: Uri, onProgress: (String) -> Unit = {}): Result<TtsVoice> = runCatching {
        val root = voicesRoot(context)
        val tmp = File(context.cacheDir, "voice_import")
        if (tmp.exists()) tmp.deleteRecursively()
        tmp.mkdirs()

        onProgress("读取压缩包…")
        var total = 0L
        val buffer = ByteArray(1 shl 16)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法打开文件" }
            ZipInputStream(input.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && !name.contains("..")) {
                        val out = File(tmp, name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { os ->
                            while (true) {
                                val n = zis.read(buffer)
                                if (n <= 0) break
                                os.write(buffer, 0, n)
                                total += n
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }

        val metaDir = runCatching {
            tmp.walkTopDown().firstOrNull { it.isDirectory && File(it, "meta.properties").exists() }
        }.getOrNull() ?: error("压缩包里没有 meta.properties")

        val displayName = runCatching {
            File(metaDir, "meta.properties").readLines()
                .firstOrNull { it.startsWith("name=") }?.substringAfter("=")?.trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: metaDir.name

        var destName = metaDir.name
        var dest = File(root, destName)
        var i = 2
        while (dest.exists()) {
            destName = "${metaDir.name}_$i"
            dest = File(root, destName)
            i++
        }
        onProgress("写入 ${destName}…")
        metaDir.copyRecursively(dest, overwrite = true)
        tmp.deleteRecursively()

        val mb = total / 1024.0 / 1024.0
        onProgress("导入完成：$displayName（%.1f MB）".format(mb))
        TtsVoice(dest.name, displayName, dest)
    }

    fun delete(voice: TtsVoice): Boolean = runCatching { voice.dir.deleteRecursively() }.getOrDefault(false)
}

/**
 * 分段流式播放器：
 * 把文本切成 ≤40 字的句子，生产者逐句合成、消费者逐句播放；
 * 合成下一句的同时播放当前句，首句合成完即可出声。
 */
class TtsPlayer(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    @Volatile
    var speaking: Boolean = false
        private set

    @Volatile
    var status: String? = null
        private set

    /** 朗读开始/结束时回调（用于情境声 ducking）。 */
    @Volatile
    var onSpeakingChanged: ((Boolean) -> Unit)? = null

    private var engine: GsvEngine? = null
    private var engineDir: String? = null
    private var track: AudioTrack? = null

    /** 已合成片段缓存（重复朗读 / 回跳同一句时直接复用）。 */
    private val cache = object : LinkedHashMap<String, ShortArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ShortArray>?): Boolean = size > 64
    }

    @Synchronized
    private fun engineFor(voice: TtsVoice): GsvEngine {
        val key = voice.dir.absolutePath
        engine?.let { if (engineDir == key && it.loaded) return it }
        engine?.close()
        val e = GsvEngine(voice.dir, TtsAssets.ensure(context))
        e.load()
        if (!e.loaded) error("引擎加载失败：${voice.name}")
        engine = e
        engineDir = key
        return e
    }

    fun speak(text: String, voice: TtsVoice?, speed: Int = 100) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        stop()
        // P2-8：在启动协程前同步置位，避免快速连点重复进入
        speaking = true
        onSpeakingChanged?.invoke(true)

        job = scope.launch {
            try {
                val v = voice ?: TtsVoices.list(context).firstOrNull() ?: run {
                    status = "没有可用的声音包"
                    return@launch
                }
                val sentences = split(clean)
                if (sentences.isEmpty()) return@launch

                status = "加载音色 ${v.name}…"
                val e = engineFor(v)

                val ch = Channel<ShortArray>(2)
                val producer = launch(Dispatchers.Default) {
                    try {
                        for ((idx, s) in sentences.withIndex()) {
                            val key = "${v.dir.name}|$speed|$s"
                            val pcm = synchronized(cache) { cache[key] }
                                ?: e.synthesize(s, speed.coerceIn(50, 200))
                            if (pcm.isNotEmpty()) {
                                synchronized(cache) { cache[key] = pcm }
                                status = "合成 ${idx + 1}/${sentences.size}…"
                                ch.send(pcm)
                            }
                        }
                    } finally {
                        ch.close()
                    }
                }

                var played = 0
                for (pcm in ch) {
                    if (!speaking) break
                    status = "播放中 ${played + 1}/${sentences.size}"
                    play(pcm)
                    played++
                }
                producer.cancel()
                status = if (played > 0) null else "没有合成出音频"
            } catch (t: Throwable) {
                status = "朗读失败：${t.message}"
            } finally {
                speaking = false
                onSpeakingChanged?.invoke(false)
            }
        }
    }

    /** 切句：优先句末标点，单句 ≤40 字；超长再按逗号切。 */
    private fun split(text: String): List<String> {
        val out = ArrayList<String>()
        val buf = StringBuilder()
        val ends = "。！？；!?;\n…"
        val commas = "，,、：:"

        fun flush(limit: Int) {
            var s = buf.toString().trim()
            buf.setLength(0)
            if (s.isEmpty()) return
            while (s.length > limit) {
                var cut = -1
                for (i in limit - 1 downTo limit / 2) {
                    if (commas.indexOf(s[i]) >= 0) { cut = i; break }
                }
                if (cut < 0) cut = limit - 1
                out.add(s.substring(0, cut + 1).trim())
                s = s.substring(cut + 1).trim()
            }
            if (s.isNotEmpty()) out.add(s)
        }

        for (c in text) {
            buf.append(c)
            if (ends.indexOf(c) >= 0) {
                flush(40)
            } else if (buf.length >= 40) {
                flush(40)
            }
        }
        flush(40)
        return out.filter { it.isNotBlank() }
    }

    private fun play(pcm: ShortArray) {
        val bytes = pcm.size * 2
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(GsvEngine.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(bytes)
            .build()
        track = t
        t.write(pcm, 0, pcm.size)
        t.play()
        val ms = (pcm.size.toLong() * 1000 / GsvEngine.SAMPLE_RATE).toInt()
        val end = System.currentTimeMillis() + ms
        while (speaking && System.currentTimeMillis() < end) {
            Thread.sleep(20)
        }
        runCatching { t.stop() }
        runCatching { t.release() }
        if (track === t) track = null
    }

    fun stop() {
        speaking = false
        job?.cancel()
        job = null
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        track = null
        status = null
    }

    fun close() {
        stop()
        engine?.close()
        engine = null
        engineDir = null
    }
}
