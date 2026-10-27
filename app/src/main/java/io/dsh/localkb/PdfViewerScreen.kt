package io.dsh.localkb

import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val VBg: Color get() = AppColors.bg
private val VAccent: Color get() = AppColors.accent
private val VMuted: Color get() = AppColors.muted

/** 简单的 PDF 原页查看器：用于引用跳转。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerScreen(db: Db, docId: Long, initialPage: Int, onBack: () -> Unit) {
    val doc = remember(docId) { db.docById(docId) }
    var page by remember { mutableStateOf((initialPage - 1).coerceAtLeast(0)) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pageCount by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(docId, page) {
        error = null
        val path = doc?.srcPath
        if (path == null) {
            error = "找不到原文件（可能是旧文档）"
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            runCatching {
                ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        pageCount = renderer.pageCount
                        val idx = page.coerceIn(0, renderer.pageCount - 1)
                        val p = renderer.openPage(idx)
                        try {
                            val w = 1000
                            val h = (w * p.height / p.width.toFloat()).toInt().coerceAtLeast(1)
                            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            b.eraseColor(AColor.WHITE)
                            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmap = b
                        } finally {
                            p.close()
                        }
                    }
                }
            }.onFailure { error = "渲染失败：${it.message}" }
        }
    }

    Scaffold(
        containerColor = VBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(doc?.name ?: "原文", fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Text("第 ${page + 1} 页 / 共 $pageCount 页", fontSize = 11.sp, color = VMuted)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AppColors.text)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = VBg, titleContentColor = AppColors.text)
            )
        },
        bottomBar = {
            Surface(color = VBg) {
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { if (page > 0) page-- }, enabled = page > 0) { Text("上一页") }
                    Text("${page + 1} / $pageCount", color = VMuted, fontSize = 12.sp)
                    OutlinedButton(
                        onClick = { if (page + 1 < pageCount) page++ },
                        enabled = page + 1 < pageCount
                    ) { Text("下一页", color = VAccent) }
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(AppColors.card)) {
            val b = bitmap
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = "PDF 第 ${page + 1} 页",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                )
            } else {
                Text(
                    error ?: "渲染中…",
                    color = VMuted,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}
