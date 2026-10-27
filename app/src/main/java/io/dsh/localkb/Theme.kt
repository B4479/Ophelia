package io.dsh.localkb

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 全局配色。
 * 三套风格（family）：bw 黑白灰蓝 / olive 奶油橄榄 / warm 暖白·暖纸
 * 每套有 light / dark 两个版本；follow=true 时按系统深色模式自动选。
 */
object AppColors {

    const val F_BW = "bw"
    const val F_OLIVE = "olive"
    const val F_WARM = "warm"

    var family by mutableStateOf(F_BW)
    var mode by mutableStateOf("light")     // light / dark
    var follow by mutableStateOf(false)     // 跟随系统

    // 语义 token
    var bg by mutableStateOf(Color(0xFFF5F6F7))
    var card by mutableStateOf(Color(0xFFFFFFFF))
    var cardAlt by mutableStateOf(Color(0xFFEDEFF1))
    var drawer by mutableStateOf(Color(0xFFF0F2F4))
    var text by mutableStateOf(Color(0xFF16181A))
    var muted by mutableStateOf(Color(0xFF6B7076))
    var accent by mutableStateOf(Color(0xFF2F6FA8))
    var onAccent by mutableStateOf(Color(0xFFFFFFFF))
    var user by mutableStateOf(Color(0xFFE8EBEF))
    var divider by mutableStateOf(Color(0xFFDCE0E4))
    var success by mutableStateOf(Color(0xFF2E7D55))
    var warn by mutableStateOf(Color(0xFFA8641C))
    var danger by mutableStateOf(Color(0xFFC0392B))

    fun load(db: Db) {
        family = db.getSetting("theme_family") ?: F_BW
        mode = db.getSetting("theme_mode") ?: "light"
        follow = db.getSetting("theme_follow") == "1"
    }

    fun save(db: Db) {
        db.setSetting("theme_family", family)
        db.setSetting("theme_mode", mode)
        db.setSetting("theme_follow", if (follow) "1" else "0")
    }

    private data class P(
        val bg: Long, val card: Long, val cardAlt: Long, val drawer: Long,
        val text: Long, val muted: Long, val accent: Long, val onAccent: Long,
        val user: Long, val divider: Long, val success: Long, val warn: Long, val danger: Long,
    )

    // 黑白灰蓝
    private val BW_LIGHT = P(
        0xFFF5F6F7, 0xFFFFFFFF, 0xFFEDEFF1, 0xFFF0F2F4,
        0xFF16181A, 0xFF6B7076, 0xFF2F6FA8, 0xFFFFFFFF,
        0xFFE8EBEF, 0xFFDCE0E4, 0xFF2E7D55, 0xFFA8641C, 0xFFC0392B,
    )
    private val BW_DARK = P(
        0xFF0C0D0F, 0xFF16181B, 0xFF1E2024, 0xFF101114,
        0xFFF2F3F5, 0xFF9BA1A8, 0xFF5B9DD9, 0xFF0A1016,
        0xFF22262B, 0xFF262A2F, 0xFF3E9B6B, 0xFFD08A3E, 0xFFE05B5B,
    )

    // 奶油橄榄
    private val OLIVE_LIGHT = P(
        0xFFFAF6EC, 0xFFFFFFFF, 0xFFF2EDDF, 0xFFF6F1E6,
        0xFF24231C, 0xFF6E6A5C, 0xFF6F7A45, 0xFFFFFFFF,
        0xFFEFEADB, 0xFFE3DCCB, 0xFF3E7A52, 0xFFA8641C, 0xFFB8402F,
    )
    private val OLIVE_DARK = P(
        0xFF14150F, 0xFF1D1F16, 0xFF262920, 0xFF171811,
        0xFFECEDE2, 0xFFA0A392, 0xFFA3B06A, 0xFF12140C,
        0xFF272A1E, 0xFF2C2F24, 0xFF8FC08F, 0xFFD9A45B, 0xFFE0705E,
    )

    // 暖白 / 暖纸
    private val WARM_LIGHT = P(
        0xFFF7F4EF, 0xFFFFFFFF, 0xFFF1ECE4, 0xFFF3EEE7,
        0xFF1F1D1A, 0xFF6E6862, 0xFFB96B3C, 0xFFFFFFFF,
        0xFFEFE6D9, 0xFFE4DCD1, 0xFF3F8F5F, 0xFFB26A1F, 0xFFC0442F,
    )
    private val WARM_DARK = P(
        0xFF171512, 0xFF211E1A, 0xFF2A2621, 0xFF1B1815,
        0xFFEFE9DF, 0xFFA69C8D, 0xFFD9A46B, 0xFF20180F,
        0xFF2C2721, 0xFF2E2A24, 0xFF7ED6A5, 0xFFFFB86B, 0xFFFF6B6B,
    )

    private fun palette(): P {
        val dark = if (follow) systemDarkNow else mode == "dark"
        return when (family) {
            F_OLIVE -> if (dark) OLIVE_DARK else OLIVE_LIGHT
            F_WARM -> if (dark) WARM_DARK else WARM_LIGHT
            else -> if (dark) BW_DARK else BW_LIGHT
        }
    }

    /** 由 MainActivity 每次系统主题变化时写入。 */
    var systemDarkNow: Boolean = false
        private set

    fun applyTheme(systemDark: Boolean) {
        systemDarkNow = systemDark
        val p = palette()
        bg = Color(p.bg); card = Color(p.card); cardAlt = Color(p.cardAlt); drawer = Color(p.drawer)
        text = Color(p.text); muted = Color(p.muted); accent = Color(p.accent); onAccent = Color(p.onAccent)
        user = Color(p.user); divider = Color(p.divider)
        success = Color(p.success); warn = Color(p.warn); danger = Color(p.danger)
    }

    @Composable
    fun AppTheme(content: @Composable () -> Unit) {
        val base = if (isDark()) darkColorScheme() else lightColorScheme()
        val scheme = base.copy(
            primary = accent, onPrimary = onAccent,
            primaryContainer = cardAlt, onPrimaryContainer = text,
            secondary = accent, onSecondary = onAccent,
            secondaryContainer = cardAlt, onSecondaryContainer = text,
            background = bg, onBackground = text,
            surface = card, onSurface = text,
            surfaceVariant = cardAlt, onSurfaceVariant = muted,
            surfaceContainer = card, surfaceContainerHigh = card,
            surfaceContainerHighest = cardAlt, surfaceContainerLow = card,
            outline = divider, outlineVariant = divider,
            error = danger, onError = Color.White,
            inverseSurface = text, inverseOnSurface = bg,
        )
        MaterialTheme(colorScheme = scheme, content = content)
    }

    fun isDark(): Boolean = bg.red < 0.5f && bg.green < 0.5f && bg.blue < 0.5f
}
