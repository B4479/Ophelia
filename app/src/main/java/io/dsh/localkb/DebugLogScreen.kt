package io.dsh.localkb

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LBg: Color get() = AppColors.bg
private val LCard: Color get() = AppColors.card
private val LAccent: Color get() = AppColors.accent
private val LMuted: Color get() = AppColors.muted

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(db: Db, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(listOf<DebugLogRow>()) }

    suspend fun reload() {
        rows = withContext(Dispatchers.IO) { db.listLogs(300) }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        containerColor = LBg,
        topBar = {
            TopAppBar(
                title = { Text("调试日志", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AppColors.text)
                    }
                },
                actions = {
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { db.clearLogs() }
                            reload()
                        }
                    }) { Text("清空", color = AppColors.danger, fontSize = 13.sp) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LBg, titleContentColor = AppColors.text)
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 14.dp)) {
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无操作记录", color = LMuted, fontSize = 13.sp)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(rows) { r ->
                        val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                            .format(java.util.Date(r.createdAt))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = LCard),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(r.kind, color = LAccent, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.width(96.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "msg=${r.messageId ?: "-"}  model=${r.model ?: "-"}",
                                        color = AppColors.text, fontSize = 11.sp
                                    )
                                    r.detail?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, color = LMuted, fontSize = 10.sp)
                                    }
                                }
                                Text(time, color = AppColors.muted, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
