package io.dsh.localkb

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** 一个情境声场景 = 一个目录里的若干循环音轨。 */
data class AmbienceScene(val name: String, val tracks: List<File>)

/**
 * 情境声目录：
 *   /sdcard/Android/media/io.dsh.localkb/ambience/<场景>/ 下的 ogg|mp3|wav|m4a
 * 也可以放一份在应用私有目录做兜底。
 */
object AmbienceStore {

    private val EXT = setOf("ogg", "mp3", "wav", "m4a", "aac", "flac", "opus")

    /** 主目录：/sdcard/Android/media/<pkg>/ambience（可用 U 盘/文件管理器直接放素材）。 */
    fun root(context: Context): File {
        val media = runCatching { context.getExternalMediaDirs()?.firstOrNull() }.getOrNull()
        val base = File(media ?: (context.getExternalFilesDir(null) ?: context.filesDir), "ambience")
        if (!base.exists()) base.mkdirs()
        return base
    }

    private fun allRoots(context: Context): List<File> {
        val out = ArrayList<File>()
        out.add(root(context))
        runCatching { context.getExternalFilesDir(null) }.getOrNull()?.let { out.add(File(it, "ambience")) }
        out.add(File(context.filesDir, "ambience"))
        return out.distinct()
    }

    fun scan(context: Context): List<AmbienceScene> {
        val roots = allRoots(context)

        val out = ArrayList<AmbienceScene>()
        for (r in roots) {
            val dirs = runCatching { r.listFiles { f -> f.isDirectory } }.getOrNull() ?: continue
            for (d in dirs) {
                val tracks = runCatching {
                    d.listFiles { f -> f.isFile && f.extension.lowercase() in EXT && f.length() > 0 }
                        ?.sortedBy { it.name }
                }.getOrNull() ?: emptyList()
                if (tracks.isNotEmpty() && out.none { it.name == d.name }) {
                    out.add(AmbienceScene(d.name, tracks))
                }
            }
        }
        return out.sortedBy { it.name }
    }
}

/**
 * 情境声播放器：MediaPlayer 循环播放，支持音量与「说话时压低」（ducking）。
 */
class AmbienceEngine(private val context: Context) {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private var player: MediaPlayer? = null
    private var fadeJob: Job? = null

    @Volatile
    var current: AmbienceScene? = null
        private set

    @Volatile
    var playing: Boolean = false
        private set

    /** 用户设定音量 0..1 */
    @Volatile
    var volume: Float = 0.6f
        private set

    @Volatile
    private var ducked: Boolean = false

    private fun effective(v: Float, duck: Boolean): Float = (v * if (duck) 0.28f else 1f).coerceIn(0f, 1f)

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        fadeTo(effective(volume, ducked), 120)
    }

    fun setScene(scene: AmbienceScene, autoplay: Boolean = true) {
        if (current?.name == scene.name && player != null) {
            if (autoplay && !playing) play()
            return
        }
        stop()
        current = scene
        if (autoplay) play()
    }

    fun play() {
        val scene = current ?: return
        val track = scene.tracks.firstOrNull() ?: return
        stop()
        runCatching {
            val mp = MediaPlayer()
            mp.setDataSource(track.absolutePath)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.prepare()
            mp.start()
            player = mp
            playing = true
            Log.i("Ambience", "play ${track.name} volume=${effective(volume, ducked)}")
            fadeTo(effective(volume, ducked), 600)
        }.onFailure {
            playing = false
            player = null
        }
    }

    fun stop() {
        fadeJob?.cancel()
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        player = null
        playing = false
    }

    /** 说话时压低，结束后恢复。 */
    fun duck(on: Boolean) {
        if (ducked == on) return
        ducked = on
        Log.i("Ambience", "duck=$on target=${effective(volume, on)}")
        fadeTo(effective(volume, on), if (on) 180 else 500)
    }

    private fun fadeTo(target: Float, ms: Long) {
        val mp = player ?: return
        fadeJob?.cancel()
        fadeJob = scope.launch {
            val start = runCatching { volumeOf(mp) }.getOrDefault(target)
            val steps = (ms / 30).coerceAtLeast(1)
            for (i in 1..steps) {
                val v = start + (target - start) * i / steps
                runCatching { mp.setVolume(v, v) }
                delay(30)
            }
        }
    }

    private fun volumeOf(mp: MediaPlayer): Float {
        // MediaPlayer 没有 getVolume()，用我们的期望值近似
        return effective(volume, ducked)
    }

    fun close() {
        stop()
        job.cancel()
    }
}
