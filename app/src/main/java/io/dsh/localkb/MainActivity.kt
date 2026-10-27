package io.dsh.localkb

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlin.math.roundToInt

private val Bg: Color get() = AppColors.bg
private val CardBg: Color get() = AppColors.card
private val Accent: Color get() = AppColors.accent
private val Muted: Color get() = AppColors.muted

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ 强制 edge-to-edge：adjustResize 不再生效，必须自己处理 IME insets
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val db = Db(applicationContext)
        setContent {
            AppColors.AppTheme {
                Surface(color = AppColors.bg, modifier = Modifier.fillMaxSize()) {
                    AppRoot(db)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(db: Db) {
    val context = LocalContext.current
    val tts = remember { AudioHub.tts(context) }
    val amb = remember { AudioHub.ambience(context) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { db.verifySchema() }
            runCatching {
                val hasApi = !db.getSetting("llm_key").isNullOrBlank() || db.listCloudModels().isNotEmpty()
                val hasLocal = Catalog.MODELS.any {
                    it.type == Catalog.TYPE_CHAT && Catalog.isInstalled(context, it)
                }
                db.ensureOnboardingState(hasApi || hasLocal)
            }
            runCatching { ImportQueue.resumeAll(context, db) }
        }
    }
    DisposableEffect(Unit) {
        AudioHub.link(context)
        runCatching {
            amb.setVolume(db.getSetting("ambience_volume")?.toFloatOrNull() ?: 0.6f)
            if (db.getSetting("ambience_on") == "1" && !amb.playing) {
                val scenes = AmbienceStore.scan(context)
                val saved = db.getSetting("ambience_scene")
                (scenes.firstOrNull { it.name == saved } ?: scenes.firstOrNull())?.let { amb.setScene(it, true) }
            }
        }
        onDispose { /* 音频单例随进程存活，不在这里销毁 */ }
    }

    // 主题：启动读设置，之后随系统深浅色 / 用户选择实时应用
    val systemDark = isSystemInDarkTheme()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { AppColors.load(db) }
        AppColors.applyTheme(systemDark)
    }
    LaunchedEffect(AppColors.family, AppColors.mode, AppColors.follow, systemDark) {
        AppColors.applyTheme(systemDark)
    }

    // 状态栏/导航栏图标颜色跟随主题深浅
    val view = LocalView.current
    val activity = LocalContext.current as? android.app.Activity
    LaunchedEffect(AppColors.bg) {
        val w = activity?.window ?: return@LaunchedEffect
        val controller = androidx.core.view.WindowCompat.getInsetsController(w, view)
        val light = !AppColors.isDark()
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
    }

    var currentKb by remember { mutableStateOf<Kb?>(null) }
    var showChat by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var settingsPage by remember { mutableStateOf("root") }
    var viewer by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    val kb = currentKb
    when {
        viewer != null -> PdfViewerScreen(db, viewer!!.first, viewer!!.second) { viewer = null }
        showSettings -> SettingsScreen(db, tts, onBack = { showSettings = false; settingsPage = "root" }, initialPage = settingsPage)
        showChat -> ChatScreen(
            db, tts, amb,
            onOpenSettings = { page -> settingsPage = page; showSettings = true },
            onOpenCitation = { docId, page -> viewer = docId to page }
        ) { showChat = false }
        kb == null -> HomeScreen(db, tts, onOpenChat = { showChat = true }) { currentKb = it }
        else -> KbScreen(db, kb, tts, amb) { currentKb = null }
    }
}

private data class KbRow(val kb: Kb, val docs: Int, val chunks: Int)

/**
 * 不再有全局音色：知识库页/工作模式用第一个可用声音包兜底；
 * 聊天模式用角色卡绑定的音色（见 ChatScreen.voiceFor）。
 */
private fun currentVoice(context: android.content.Context, db: Db): TtsVoice? =
    TtsVoices.list(context).firstOrNull()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HomeScreen(db: Db, tts: TtsPlayer, onOpenChat: () -> Unit, onOpen: (Kb) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var rows by remember { mutableStateOf(listOf<KbRow>()) }
    var showCreate by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var actionKb by remember { mutableStateOf<KbRow?>(null) }
    var editKb by remember { mutableStateOf<KbRow?>(null) }
    var deleteKb by remember { mutableStateOf<KbRow?>(null) }
    var editName by remember { mutableStateOf("") }
    var editNote by remember { mutableStateOf("") }
    var topkKb by remember { mutableStateOf<KbRow?>(null) }
    var topkValue by remember { mutableStateOf(5f) }
    var topkSaveJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun fetchRows(): List<KbRow> = withContext(Dispatchers.IO) {
        db.listKbs().map { KbRow(it, db.listDocs(it.id).size, db.chunkCount(it.id)) }
    }

    LaunchedEffect(Unit) { rows = fetchRows() }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text("Ophelia", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = AppColors.text),
                actions = {
                    IconButton(onClick = onOpenChat) {
                        Icon(Icons.Filled.Chat, contentDescription = "对话", tint = AppColors.text)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreate = true }, containerColor = Accent) {
                Icon(Icons.Filled.Add, contentDescription = "新建知识库", tint = AppColors.onAccent)
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有知识库，点右下角 + 新建一个", color = Muted)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                    items(rows) { row ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = CardBg),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().combinedClickable(
                                onClick = { onOpen(row.kb) },
                                onLongClick = { actionKb = row }
                            )
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(row.kb.name, color = AppColors.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                val note = row.kb.note.orEmpty()
                                if (note.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(note, color = AppColors.text, fontSize = 12.sp, maxLines = 2)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("${row.docs} 个文档 · ${row.chunks} 个片段", color = Muted, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    actionKb?.let { row ->
        AlertDialog(
            onDismissRequest = { actionKb = null },
            title = { Text(row.kb.name) },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text("修改", color = AppColors.text) },
                        supportingContent = { Text("修改名称与简介", color = Muted, fontSize = 12.sp) },
                        modifier = Modifier.clickable {
                            editName = row.kb.name
                            editNote = row.kb.note.orEmpty()
                            actionKb = null
                            editKb = row
                        }
                    )
                    ListItem(
                        headlineContent = { Text("检索参数", color = AppColors.text) },
                        supportingContent = { Text("TopK：每次检索返回几条片段", color = Muted, fontSize = 12.sp) },
                        modifier = Modifier.clickable {
                            topkValue = row.kb.topK.toFloat()
                            actionKb = null
                            topkKb = row
                        }
                    )
                    ListItem(
                        headlineContent = { Text("删除", color = AppColors.danger) },
                        supportingContent = { Text("删除该知识库及其全部文档", color = Muted, fontSize = 12.sp) },
                        modifier = Modifier.clickable {
                            actionKb = null
                            deleteKb = row
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { actionKb = null }) { Text("取消") } }
        )
    }

    topkKb?.let { row ->
        AlertDialog(
            onDismissRequest = { topkKb = null },
            title = { Text("检索参数 · ${row.kb.name}") },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("当前：${topkValue.roundToInt()} 条", color = Accent, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Slider(
                        value = topkValue,
                        onValueChange = { v ->
                            topkValue = v
                            topkSaveJob?.cancel()
                            topkSaveJob = scope.launch {
                                delay(300)
                                withContext(Dispatchers.IO) {
                                    db.updateKbTopK(row.kb.id, v.roundToInt())
                                }
                                rows = fetchRows()
                            }
                        },
                        valueRange = 1f..10f,
                        steps = 8,
                        colors = SliderDefaults.colors(
                            thumbColor = Accent,
                            activeTrackColor = Accent,
                            inactiveTrackColor = AppColors.cardAlt
                        )
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("精确检索", color = Muted, fontSize = 11.sp)
                        Text("全面检索", color = Muted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "TopK 越高 → 注入上下文的检索片段越多 → 消耗 token 越多",
                        color = AppColors.muted, fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    topkValue = 5f
                    topkSaveJob?.cancel()
                    scope.launch {
                        withContext(Dispatchers.IO) { db.updateKbTopK(row.kb.id, 5) }
                        rows = fetchRows()
                    }
                }) { Text("恢复默认") }
            },
            dismissButton = { TextButton(onClick = { topkKb = null }) { Text("关闭") } }
        )
    }

    editKb?.let { row ->
        AlertDialog(
            onDismissRequest = { editKb = null },
            title = { Text("修改知识库") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editNote,
                        onValueChange = { editNote = it },
                        label = { Text("简介（可选）") },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = editName.trim()
                    if (n.isNotEmpty()) {
                        scope.launch {
                            withContext(Dispatchers.IO) { db.updateKb(row.kb.id, n, editNote.trim()) }
                            editKb = null
                            rows = fetchRows()
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editKb = null }) { Text("取消") } }
        )
    }

    deleteKb?.let { row ->
        AlertDialog(
            onDismissRequest = { deleteKb = null },
            title = { Text("删除知识库") },
            text = {
                Text(
                    "将删除知识库《${row.kb.name}》及其 ${row.docs} 个文档、${row.chunks} 个片段，不可恢复。",
                    color = AppColors.text, fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            // 1) 先取消该库所有后台导入，避免删除后继续写入
                            ImportQueue.cancelAllForKb(context, db, row.kb.id)
                            // 2) 删私有副本文件
                            db.listDocs(row.kb.id).forEach { d ->
                                d.srcPath?.let { runCatching { java.io.File(it).delete() } }
                            }
                            // 3) 事务化删库
                            db.deleteKb(row.kb.id)
                            Hybrid.invalidate(row.kb.id)
                        }
                        deleteKb = null
                        rows = fetchRows()
                    }
                }) { Text("确认删除", color = AppColors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleteKb = null }) { Text("取消") } }
        )
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建知识库") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = name.trim()
                    if (n.isNotEmpty()) {
                        scope.launch {
                            withContext(Dispatchers.IO) { db.createKb(n) }
                            name = ""
                            showCreate = false
                            rows = fetchRows()
                        }
                    }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } }
        )
    }

}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun KbScreen(db: Db, kb: Kb, tts: TtsPlayer, amb: AmbienceEngine, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var docs by remember { mutableStateOf(listOf<DocItem>()) }
    var progress by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf(listOf<Hit>()) }
    var answer by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAmbience by remember { mutableStateOf(false) }
    var noticeCount by remember { mutableStateOf(0) }

    suspend fun reloadDocs() {
        docs = withContext(Dispatchers.IO) { db.listDocs(kb.id) }
    }
    LaunchedEffect(kb.id) {
        while (true) {
            reloadDocs()
            if (docs.any { it.status == "queued" || it.status == "parsing" || it.status.startsWith("embedding") || it.status == "paused" }) {
                Hybrid.invalidate(kb.id)
            }
            delay(1500)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            for (uri in uris) {
                val displayName = queryName(context, uri)
                if (!Extract.isSupported(displayName)) {
                    error = "暂不支持这种格式：$displayName"
                    continue
                }
                runCatching {
                    withContext(Dispatchers.IO) { ImportQueue.enqueue(context, db, kb.id, uri, displayName) }
                }.onFailure { error = "加入导入队列失败：${it.message}" }
            }
            busy = false
            Hybrid.invalidate(kb.id)
            reloadDocs()
        }
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val files = withContext(Dispatchers.IO) { listPdfInTree(context, tree) }
            progress = "目录里找到 ${files.size} 个 PDF，正在加入队列…"
            var ok = 0
            for ((uri, name) in files) {
                val r = runCatching {
                    withContext(Dispatchers.IO) { ImportQueue.enqueue(context, db, kb.id, uri, name) }
                }
                if (r.isSuccess) ok++ else error = "部分文件入队失败：${r.exceptionOrNull()?.message}"
            }
            progress = null
            busy = false
            Hybrid.invalidate(kb.id)
            reloadDocs()
            noticeCount = ok
        }
    }

    Scaffold(
        containerColor = Bg,
        topBar = {
            TopAppBar(
                title = { Text(kb.name, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "返回", tint = AppColors.text) }
                },
                actions = {
                    IconButton(onClick = { if (!busy) treePicker.launch(null) }) {
                        Icon(Icons.Filled.FolderOpen, "导入整个目录", tint = AppColors.text)
                    }
                    IconButton(onClick = { showAmbience = true }) {
                        Icon(
                            Icons.Filled.MusicNote, "情境声",
                            tint = if (amb.playing) Accent else AppColors.text
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = AppColors.text)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { if (!busy) picker.launch(arrayOf("*/*")) },
                containerColor = Accent
            ) { Icon(Icons.Filled.Add, "导入 PDF", tint = AppColors.onAccent) }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("提问或搜索…", color = Muted) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    enabled = !busy,
                    onClick = {
                        val q = query.trim()
                        if (q.isEmpty()) return@IconButton
                        scope.launch {
                            busy = true; error = null; answer = null
                            try {
                                val emb = withContext(Dispatchers.Default) { EmbedderHolder.get(context).embed(q) }
                                val found = withContext(Dispatchers.IO) { Hybrid.search(db, kb.id, q, emb, kb.topK) }
                                hits = found
                                val cfg = Llm.loadConfig(db)
                                if (cfg != null && found.isNotEmpty()) {
                                    val r = withContext(Dispatchers.IO) { Llm.answer(cfg, q, found) }
                                    answer = r.getOrElse { "（生成失败：${it.message}）" }
                                }
                            } catch (t: Throwable) {
                                error = t.message
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Icon(Icons.Filled.Search, "搜索", tint = Accent) }
            }

            if (docs.isNotEmpty() && hits.isEmpty() && answer == null) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("资本", "国家", "工人", "危机").forEach { sample ->
                        AssistChip(
                            onClick = { query = sample },
                            label = { Text(sample, fontSize = 12.sp) }
                        )
                    }
                }
            }

            val p = progress
            if (p != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(p, color = Accent, fontSize = 12.sp)
            }
            tts.status?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = AppColors.warn, fontSize = 11.sp)
            }
            if (noticeCount > 0) {
                Spacer(Modifier.height(6.dp))
                Text("已加入导入队列：$noticeCount 个文件", color = AppColors.success, fontSize = 11.sp)
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = AppColors.danger, fontSize = 12.sp)
            }

            Spacer(Modifier.height(10.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                answer?.let { a ->
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = AppColors.cardAlt),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(a, color = AppColors.text, fontSize = 14.sp)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    IconButton(onClick = {
                                        if (tts.speaking) tts.stop()
                                        else tts.speak(a, currentVoice(context, db))
                                    }) {
                                        Icon(
                                            if (tts.speaking) Icons.Filled.Stop else Icons.Filled.VolumeUp,
                                            "朗读回答", tint = Accent
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (hits.isNotEmpty()) {
                    item { Text("检索到的片段（长按朗读）", color = Muted, fontSize = 12.sp) }
                    items(hits) { h ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = CardBg),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { tts.speak(h.text, currentVoice(context, db)) }
                            )
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    "《${h.docName}》第 ${h.page} 页 · ${"%.3f".format(h.score)}",
                                    color = Accent, fontSize = 11.sp
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(h.text, color = AppColors.text, fontSize = 13.sp)
                            }
                        }
                    }
                } else if (docs.isNotEmpty()) {
                    item { Text("文档", color = Muted, fontSize = 12.sp) }
                    items(docs) { d ->
                        Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(d.name, color = AppColors.text, fontSize = 14.sp)
                                        val kindLabel = when (d.kind) {
                                            "pdf" -> "PDF"; "epub" -> "EPUB"; "docx" -> "DOCX"
                                            "pptx" -> "PPTX"; "xlsx" -> "XLSX"; "rtf" -> "RTF"
                                            "image" -> "图片OCR"; "csv" -> "CSV"; else -> "文本"
                                        }
                                        val statusText = when {
                                            d.status == "ready" -> "$kindLabel · ${d.pages} " + (if (d.kind == "pdf") "页" else "段") + " · ${d.chars} 字"
                                            d.status == "empty" -> "没有可提取的文字（可能是扫描件）"
                                            d.status == "failed" -> "导入失败"
                                            d.status == "paused" -> "已暂停 ${d.pagesDone}/${d.pages} 页，会自动续传"
                                            d.pages > 0 -> "导入中 ${d.pagesDone}/${d.pages} 页"
                                            else -> "排队中…"
                                        }
                                        Text(
                                            statusText,
                                            color = if (d.status == "ready") Muted else AppColors.warn,
                                            fontSize = 11.sp
                                        )
                                    }
                                    IconButton(onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { ImportQueue.deleteDoc(context, db, d) }
                                            Hybrid.invalidate(kb.id)
                                            reloadDocs()
                                        }
                                    }) { Icon(Icons.Filled.Delete, "删除", tint = Muted) }
                                }
                                if (d.status != "ready" && d.status != "empty" && d.status != "failed") {
                                    Spacer(Modifier.height(6.dp))
                                    if (d.pages > 0) {
                                        LinearProgressIndicator(
                                            progress = (d.pagesDone.toFloat() / d.pages).coerceIn(0f, 1f),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    } else {
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }
                } else {
                    item {
                        Text(
                            "点右下角 + 导入 PDF。导入后会本地分块、用 bge-small-zh 向量化，全部在手机上完成。",
                            color = Muted, fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }

    if (showAmbience) AmbienceDialog(db, amb) { showAmbience = false }
}

@Composable
private fun AmbienceDialog(db: Db, amb: AmbienceEngine, onClose: () -> Unit) {
    val context = LocalContext.current
    var scenes by remember { mutableStateOf(AmbienceStore.scan(context)) }
    var enabled by remember { mutableStateOf(amb.playing) }
    var volume by remember { mutableStateOf(amb.volume) }
    var sceneName by remember { mutableStateOf(amb.current?.name ?: db.getSetting("ambience_scene")) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("情境声") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("播放情境声", color = AppColors.text, modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { on ->
                        enabled = on
                        db.setSetting("ambience_on", if (on) "1" else "0")
                        if (on) {
                            val sc = scenes.firstOrNull { it.name == sceneName } ?: scenes.firstOrNull()
                            if (sc != null) {
                                sceneName = sc.name
                                db.setSetting("ambience_scene", sc.name)
                                amb.setScene(sc, true)
                            }
                        } else {
                            amb.stop()
                        }
                    })
                }

                if (scenes.isEmpty()) {
                    Text(
                        """还没有情境声素材
把音频放到：
/sdcard/Android/media/io.dsh.localkb/ambience/<场景名>/
支持 ogg / mp3 / wav / m4a。""",
                        color = Muted, fontSize = 12.sp
                    )
                } else {
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        items(scenes) { sc ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = sceneName == sc.name, onClick = {
                                    sceneName = sc.name
                                    db.setSetting("ambience_scene", sc.name)
                                    if (enabled) amb.setScene(sc, true)
                                })
                                Text(sc.name, color = AppColors.text, modifier = Modifier.weight(1f))
                                Text("${sc.tracks.size} 段", color = Muted, fontSize = 11.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("音量 ${(volume * 100).toInt()}%", color = Accent, fontSize = 12.sp)
                Slider(
                    value = volume,
                    onValueChange = {
                        volume = it
                        amb.setVolume(it)
                        db.setSetting("ambience_volume", it.toString())
                    },
                    valueRange = 0f..1f
                )
                Text("说话时会自动压低到 28%，说完恢复。", color = Muted, fontSize = 11.sp)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scenes = AmbienceStore.scan(context) }) { Text("重新扫描") }
                    OutlinedButton(onClick = { amb.stop(); enabled = false; db.setSetting("ambience_on", "0") }) {
                        Text("停止")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } }
    )
}

private fun listPdfInTree(
    context: android.content.Context,
    tree: android.net.Uri,
): List<Pair<android.net.Uri, String>> {
    val out = ArrayList<Pair<android.net.Uri, String>>()
    runCatching {
        val resolver = context.contentResolver
        val rootId = android.provider.DocumentsContract.getTreeDocumentId(tree)
        val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
        resolver.query(
            children,
            arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: "未命名.pdf"
                val mime = c.getString(2).orEmpty()
                if (Extract.isSupported(name)) {
                    out.add(
                        android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id) to name
                    )
                }
            }
        }
    }
    return out
}

private fun queryName(context: android.content.Context, uri: android.net.Uri): String {
    var name = "文档.pdf"
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
    }
    return name
}
