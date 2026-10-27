package io.dsh.localkb

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class SchemeOption(
    val family: String,
    val title: String,
    val lightDesc: String,
    val darkDesc: String,
)

private val OPTIONS = listOf(
    SchemeOption(AppColors.F_BW, "黑白灰蓝", "冷白纸 · 钢蓝强调", "近黑中性灰 · 钢蓝强调"),
    SchemeOption(AppColors.F_OLIVE, "奶油橄榄", "奶油纸 · 橄榄绿", "橄榄夜 · 橄榄绿"),
    SchemeOption(AppColors.F_WARM, "暖白 · 暖纸", "米白纸 · 陶土橙", "暖黑 · 墨白字"),
)

/** 配色方案：选风格 + 浅色/深色 + 跟随系统。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeScreen(db: Db, onBack: () -> Unit) {
    var family by remember { mutableStateOf(AppColors.family) }
    var mode by remember { mutableStateOf(AppColors.mode) }
    var follow by remember { mutableStateOf(AppColors.follow) }

    fun commit() {
        AppColors.family = family
        AppColors.mode = mode
        AppColors.follow = follow
        AppColors.applyTheme(AppColors.systemDarkNow)
        AppColors.save(db)
    }

    Scaffold(
        containerColor = AppColors.bg,
        topBar = {
            TopAppBar(
                title = { Text("配色方案", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AppColors.text)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppColors.bg, titleContentColor = AppColors.text
                )
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text("配色方案", color = AppColors.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))

            OPTIONS.forEach { opt ->
                val selected = family == opt.family
                Row(
                    Modifier.fillMaxWidth()
                        .background(AppColors.card, RoundedCornerShape(14.dp))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) AppColors.accent else AppColors.divider,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable {
                            family = opt.family
                            follow = false
                            commit()
                        }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(opt.title, color = AppColors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (mode == "dark") opt.darkDesc else opt.lightDesc,
                            color = AppColors.muted, fontSize = 12.sp
                        )
                    }
                    if (selected) {
                        Icon(Icons.Filled.Check, "已选", tint = AppColors.accent, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(8.dp))
            Text("模式", color = AppColors.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeButton("浅色", mode == "light", Modifier.weight(1f)) {
                    mode = "light"
                    follow = false
                    commit()
                }
                ModeButton("深色", mode == "dark", Modifier.weight(1f)) {
                    mode = "dark"
                    follow = false
                    commit()
                }
            }
            if (follow) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "跟随系统生效中：当前为" + if (mode == "dark") "深色" else "浅色",
                    color = AppColors.muted, fontSize = 11.sp
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth()
                    .background(AppColors.card, RoundedCornerShape(14.dp))
                    .clickable {
                        follow = !follow
                        commit()
                    }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("跟随系统", color = AppColors.text, fontSize = 15.sp)
                    Text(
                        "打开后按系统深色模式自动切换昼夜（白天用当前浅色，夜晚用对应深色）",
                        color = AppColors.muted, fontSize = 12.sp
                    )
                }
                Switch(
                    checked = follow,
                    onCheckedChange = {
                        follow = it
                        commit()
                    }
                )
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "说明：手动点选任一方案或浅色/深色按钮，会自动关闭「跟随系统」。",
                color = AppColors.muted, fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun ModeButton(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        color = if (selected) AppColors.accent else AppColors.card,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .border(
                width = 1.dp,
                color = if (selected) AppColors.accent else AppColors.divider,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
    ) {
        Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(
                text,
                color = if (selected) AppColors.onAccent else AppColors.text,
                fontSize = 14.sp, fontWeight = FontWeight.Medium
            )
        }
    }
}
