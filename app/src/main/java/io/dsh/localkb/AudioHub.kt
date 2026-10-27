package io.dsh.localkb

import android.content.Context

/**
 * 进程级音频单例：TtsPlayer / AmbienceEngine 不随界面重建而销毁，
 * 否则页面重建（配置变化、返回重进）会丢掉正在播放的情境声与 ducking 状态。
 */
object AudioHub {

    @Volatile
    private var ttsInstance: TtsPlayer? = null

    @Volatile
    private var ambienceInstance: AmbienceEngine? = null

    fun tts(context: Context): TtsPlayer = ttsInstance ?: synchronized(this) {
        ttsInstance ?: TtsPlayer(context.applicationContext).also { ttsInstance = it }
    }

    fun ambience(context: Context): AmbienceEngine = ambienceInstance ?: synchronized(this) {
        ambienceInstance ?: AmbienceEngine(context.applicationContext).also { ambienceInstance = it }
    }

    /** 由界面在启动时接线一次即可（重复调用无副作用）。 */
    fun link(context: Context) {
        val a = ambience(context)
        tts(context).onSpeakingChanged = { speaking -> a.duck(speaking) }
    }
}
