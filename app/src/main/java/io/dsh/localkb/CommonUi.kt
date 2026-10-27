package io.dsh.localkb

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

/**
 * 统一头像组件：有本地图片就显示图片，否则用文字或图标兜底。
 * - 角色卡/个人主页：传 path
 * - 默认 AI：fallbackText = "AI"
 * - 未设置个人头像：fallbackIcon = Person
 */
@Composable
fun AvatarCircle(
    path: String?,
    size: Dp,
    fallbackText: String? = null,
    fallbackIcon: ImageVector? = null,
) {
    val bmp = remember(path) {
        path?.takeIf { it.isNotBlank() }?.let { p ->
            runCatching { BitmapFactory.decodeFile(p) }.getOrNull()
        }
    }
    Box(
        Modifier.size(size).clip(CircleShape).background(AppColors.cardAlt),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (fallbackText != null) {
            Text(fallbackText, color = AppColors.muted, fontSize = (size.value * 0.36f).sp)
        } else {
            Icon(
                fallbackIcon ?: Icons.Filled.Person,
                contentDescription = "头像",
                tint = AppColors.muted,
                modifier = Modifier.size(size * 0.5f)
            )
        }
    }
}
