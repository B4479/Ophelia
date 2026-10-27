package io.dsh.localkb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * 本地识图（Qwen2-VL-2B + mmproj，经 llama.cpp mtmd）。
 * 模型文件放在 files/models/ 下，由 LocalModels 负责下载/删除。
 */
object LocalVision {

    init {
        System.loadLibrary("llama_bridge")
    }

    private var handle: Long = 0L
    private var loadedKey: String? = null
    private val lock = Any()

    private external fun nativeLoad(modelPath: String, mmprojPath: String, nThreads: Int): Long
    private external fun nativeDescribe(
        handle: Long, rgb: ByteArray, width: Int, height: Int, prompt: String, maxTokens: Int
    ): String
    private external fun nativeIsReady(handle: Long): Boolean
    private external fun nativeFree(handle: Long)

    fun isReady(context: Context): Boolean = synchronized(lock) {
        handle != 0L && nativeIsReady(handle)
    }

    fun isInstalled(context: Context): Boolean =
        LocalModels.isInstalled(context, LocalModels.VISION) &&
            LocalModels.isInstalled(context, LocalModels.VISION_MMPROJ)

    /** 加载模型（首次调用约 2–5 秒）。 */
    fun ensureLoaded(context: Context): Boolean = synchronized(lock) {
        if (handle != 0L && nativeIsReady(handle)) return true
        if (!isInstalled(context)) return false
        val model = LocalModels.file(context, LocalModels.VISION).absolutePath
        val mmproj = LocalModels.file(context, LocalModels.VISION_MMPROJ).absolutePath
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        handle = nativeLoad(model, mmproj, threads)
        handle != 0L
    }

    fun unload() = synchronized(lock) { unloadLocked() }

    private fun unloadLocked() {
        if (handle != 0L) {
            runCatching { nativeFree(handle) }
            handle = 0L
            loadedKey = null
        }
    }

    /** 识别一张图片，返回描述/识别出的文字。 */
    /** 用 Catalog 里选定的识图模型。 */
    fun describeModel(
        context: Context,
        model: Catalog.Model,
        imagePath: String,
        prompt: String,
        maxTokens: Int = 256,
    ): Result<String> = runCatching {
        synchronized(lock) {
            val main = Catalog.file(context, model)
            val proj = Catalog.mmprojFile(context, model)
            if (!main.isFile || proj == null || !proj.isFile) error("本地识图模型未安装")
            if (handle == 0L || loadedKey != model.id) {
                unloadLocked()
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
                handle = nativeLoad(main.absolutePath, proj.absolutePath, threads)
                if (handle == 0L) error("本地识图模型加载失败")
                loadedKey = model.id
            }
            val bmp = decodeScaled(imagePath) ?: error("图片读取失败")
            val rgb = toRgb(bmp)
            val w = bmp.width
            val h = bmp.height
            bmp.recycle()
            nativeDescribe(handle, rgb, w, h, prompt, maxTokens).ifBlank { "（没有识别出内容）" }
        }
    }

    fun describe(context: Context, imagePath: String, prompt: String, maxTokens: Int = 256): Result<String> =
        runCatching {
            synchronized(lock) {
                if (!ensureLoadedLocked(context)) error("本地识图模型未安装")
                val bmp = decodeScaled(imagePath) ?: error("图片读取失败")
                val rgb = toRgb(bmp)
                val w = bmp.width
                val h = bmp.height
                bmp.recycle()
                val out = nativeDescribe(handle, rgb, w, h, prompt, maxTokens)
                out.ifBlank { "（没有识别出内容）" }
            }
        }

    private fun ensureLoadedLocked(context: Context): Boolean {
        if (handle != 0L && nativeIsReady(handle)) return true
        if (!isInstalled(context)) return false
        val model = LocalModels.file(context, LocalModels.VISION).absolutePath
        val mmproj = LocalModels.file(context, LocalModels.VISION_MMPROJ).absolutePath
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        handle = nativeLoad(model, mmproj, threads)
        return handle != 0L
    }

    private fun decodeScaled(path: String, maxSide: Int = 768): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > maxSide) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, o2)
    }

    private fun toRgb(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 3)
        var j = 0
        for (p in pixels) {
            out[j++] = ((p shr 16) and 0xFF).toByte()
            out[j++] = ((p shr 8) and 0xFF).toByte()
            out[j++] = (p and 0xFF).toByte()
        }
        return out
    }
}
