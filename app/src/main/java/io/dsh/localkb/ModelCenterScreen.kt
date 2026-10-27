package io.dsh.localkb

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TABS = listOf(
    Catalog.TYPE_CHAT to "对话",
    Catalog.TYPE_VISION to "识图",
    Catalog.TYPE_EMBED to "向量",
)

/** 模型中心：云端模型 + 下载源模型。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelCenterScreen(db: Db, onBack: () -> Unit) {
    var tabIndex by remember { mutableStateOf(0) }
    val type = TABS[tabIndex].first

    var cloudList by remember { mutableStateOf(listOf<CloudModel>()) }
    var selChat by remember { mutableStateOf(ModelPrefs.chatSel(db)) }
    var selVision by remember { mutableStateOf(ModelPrefs.visionSel(db)) }
    var installing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var progress by remember { mutableStateOf<Map<String, Pair<Float, String>>>(emptyMap()) }
    var showAddCloud by remember { mutableStateOf(false) }
    var editCloud by remember { mutableStateOf<CloudModel?>(null) }
    var showSources by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Pair<CloudModel?, Catalog.Model?>?>(null) }
    var tick by remember { mutableStateOf(0) }   // 刷新已安装状态
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    suspend fun reloadCloud() {
        cloudList = withContext(Dispatchers.IO) { db.listCloudModels(type) }
    }
    LaunchedEffect(type) { reloadCloud() }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { CloudPresets.seedIfNeeded(db) } }
        reloadCloud()
        // 首页引导：配置云端 → 直接打开添加弹窗；下载本地 → 停在“其它模型”
        when (db.getSetting("guide_target")) {
            "cloud" -> { db.setSetting("guide_target", ""); showAddCloud = true }
            "local" -> { db.setSetting("guide_target", ""); tabIndex = 0 }
        }
    }

    fun currentSel(): String = when (type) {
        Catalog.TYPE_CHAT -> selChat
        Catalog.TYPE_VISION -> selVision
        else -> ModelPrefs.embedSel(db)
    }

    fun applySelection(value: String) {
        when (type) {
            Catalog.TYPE_CHAT -> { selChat = value; ModelPrefs.setSel(db, ModelPrefs.KEY_CHAT, value) }
            Catalog.TYPE_VISION -> { selVision = value; ModelPrefs.setSel(db, ModelPrefs.KEY_VISION, value) }
            else -> ModelPrefs.setSel(db, ModelPrefs.KEY_EMBED, value)
        }
        ModelPrefs.syncLegacy(db, selChat, selVision)
    }

    Scaffold(
        containerColor = AppColors.bg,
        topBar = {
            TopAppBar(
                title = { Text("模型选择", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AppColors.text)
                    }
                },
                actions = {
                    IconButton(onClick = { showSources = true }) {
                        Icon(Icons.Filled.Source, "下载源管理", tint = AppColors.accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.bg, titleContentColor = AppColors.text)
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tabIndex, containerColor = AppColors.bg, contentColor = AppColors.accent) {
                TABS.forEachIndexed { i, (_, label) ->
                    Tab(selected = tabIndex == i, onClick = { tabIndex = i }) {
                        Text(label, fontSize = 14.sp, modifier = Modifier.padding(vertical = 12.dp))
                    }
                }
            }

            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                if (type != Catalog.TYPE_EMBED) {
                    item { SectionTitle("云端模型") }
                }

                if (type != Catalog.TYPE_EMBED && cloudList.isEmpty()) {
                    item {
                        Row(
                            Modifier.fillMaxWidth()
                                .background(AppColors.card, RoundedCornerShape(14.dp))
                                .border(1.dp, AppColors.divider, RoundedCornerShape(14.dp))
                                .clickable { showAddCloud = true }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.CloudQueue, null, tint = AppColors.accent)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("未配置云端 API，点击去配置", color = AppColors.text, fontSize = 14.sp)
                                Text("填写 API 地址 + 密钥 + 模型别名", color = AppColors.muted, fontSize = 11.sp)
                            }
                        }
                    }
                } else if (type != Catalog.TYPE_EMBED) {
                    items(cloudList) { cm ->
                        val selected = currentSel() == "cloud:${cm.id}"
                        ModelRow(
                            icon = { Icon(Icons.Filled.CloudQueue, null, tint = AppColors.accent, modifier = Modifier.size(26.dp)) },
                            name = cm.name,
                            desc = cm.notes?.takeIf { it.isNotBlank() } ?: "${cm.model} · ${cm.baseUrl}",
                            source = "云端 · ${cm.model}",
                            selected = selected,
                            actions = {
                                if (selected) {
                                    Chip("使用中", AppColors.accent)
                                } else {
                                    TextButton(onClick = { applySelection("cloud:${cm.id}") }) { Text("使用", fontSize = 12.sp) }
                                }
                                IconButton(onClick = { editCloud = cm }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Edit, "编辑", tint = AppColors.muted, modifier = Modifier.size(16.dp))
                                }
                                IconButton(onClick = { confirmDelete = cm to null }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Delete, "删除", tint = AppColors.danger, modifier = Modifier.size(16.dp))
                                }
                            }
                        )
                    }
                    item {
                        TextButton(onClick = { showAddCloud = true }) {
                            Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("添加云端模型", fontSize = 12.sp)
                        }
                    }
                }

                item { Spacer(Modifier.height(6.dp)) }
                item { SectionTitle("其它模型（下载源）") }

                items(Catalog.byType(type)) { m ->
                    val installed = Catalog.isInstalled(context, m)
                    val busy = installing.contains(m.id)
                    val sel = currentSel() == "local:${m.id}"
                    ModelRow(
                        icon = {
                            Image(
                                painter = painterResource(m.iconRes),
                                contentDescription = m.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
                            )
                        },
                        name = m.name,
                        desc = m.desc,
                        source = if (m.builtin) "内置" else "${m.source} · ${m.sizeLabel}",
                        selected = sel,
                        actions = {
                            when {
                                busy -> {
                                    val p = progress[m.id]
                                    Column(horizontalAlignment = Alignment.End) {
                                        LinearProgressIndicator(
                                            progress = { p?.first ?: 0f },
                                            modifier = Modifier.width(64.dp),
                                            color = AppColors.accent
                                        )
                                        Text(p?.second ?: "下载中…", color = AppColors.muted, fontSize = 9.sp)
                                    }
                                }
                                !installed -> {
                                    Button(
                                        onClick = {
                                            installing = installing + m.id
                                            scope.launch {
                                                val r = withContext(Dispatchers.IO) {
                                                    Catalog.download(context, m) { p, text ->
                                                        progress = progress + (m.id to (p to text))
                                                    }
                                                }
                                                installing = installing - m.id
                                                progress = progress - m.id
                                                if (r.isFailure) {
                                                    db.log("model_download_failed", null, m.id, r.exceptionOrNull()?.message?.take(120))
                                                } else {
                                                    tick++
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = AppColors.accent, contentColor = AppColors.onAccent
                                        ),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                    ) { Text("下载", fontSize = 12.sp) }
                                }
                                sel -> Chip("使用中", AppColors.accent)
                                else -> {
                                    TextButton(onClick = { applySelection("local:${m.id}") }) { Text("使用", fontSize = 12.sp) }
                                    if (!m.builtin) {
                                        IconButton(onClick = { confirmDelete = null to m }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Filled.Delete, "卸载", tint = AppColors.muted, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    )
                }

                if (type == Catalog.TYPE_VISION) {
                    item {
                        Text(
                            "识图模型独立选用：对话里发图片时用这里的模型识别，和对话模型互不影响。",
                            color = AppColors.muted, fontSize = 11.sp
                        )
                    }
                }
                if (type == Catalog.TYPE_EMBED) {
                    item {
                        Text(
                            "向量模型用于知识库检索，云端向量暂不支持，当前仅内置 bge-small；换用其它向量模型需要重建索引。",
                            color = AppColors.muted, fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }

    if (showAddCloud || editCloud != null) {
        CloudModelDialog(
            db = db,
            type = type,
            existing = editCloud,
            onDismiss = { showAddCloud = false; editCloud = null },
            onSaved = {
                showAddCloud = false
                editCloud = null
                scope.launch { reloadCloud() }
            }
        )
    }

    if (showSources) {
        ModelSourcesScreen(db) { showSources = false }
    }

    confirmDelete?.let { (cloud, local) ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = AppColors.card,
            titleContentColor = AppColors.text,
            textContentColor = AppColors.text,
            title = { Text(if (cloud != null) "删除云端模型" else "卸载模型") },
            text = {
                Text(
                    if (cloud != null) "删除「${cloud.name}」的云端配置？"
                    else "卸载「${local?.name}」？已下载的文件会删除，需重新下载。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (cloud != null) {
                        scope.launch {
                            withContext(Dispatchers.IO) { db.deleteCloudModel(cloud.id) }
                            if (ModelPrefs.chatSel(db) == "cloud:${cloud.id}") {
                                ModelPrefs.setSel(db, ModelPrefs.KEY_CHAT, ModelPrefs.DEFAULT_CHAT)
                                selChat = ModelPrefs.DEFAULT_CHAT
                            }
                            if (ModelPrefs.visionSel(db) == "cloud:${cloud.id}") {
                                ModelPrefs.setSel(db, ModelPrefs.KEY_VISION, ModelPrefs.DEFAULT_VISION)
                                selVision = ModelPrefs.DEFAULT_VISION
                            }
                            reloadCloud()
                        }
                    } else if (local != null) {
                        scope.launch {
                            withContext(Dispatchers.IO) { Catalog.delete(context, local) }
                            tick++
                        }
                    }
                    confirmDelete = null
                }) { Text("确认", color = AppColors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = AppColors.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun Chip(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(10.dp)) {
        Text(text, color = color, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun ModelRow(
    icon: @Composable () -> Unit,
    name: String,
    desc: String,
    source: String,
    selected: Boolean,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(AppColors.card, RoundedCornerShape(14.dp))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) AppColors.accent else AppColors.divider,
                RoundedCornerShape(14.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = AppColors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                desc,
                color = AppColors.muted, fontSize = 11.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            Text(source, color = AppColors.muted.copy(alpha = 0.75f), fontSize = 10.sp, maxLines = 1)
        }
        Spacer(Modifier.width(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
private fun CloudModelDialog(
    db: Db,
    type: String,
    existing: CloudModel?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var base by remember { mutableStateOf(existing?.baseUrl ?: "") }
    var key by remember { mutableStateOf(existing?.apiKey ?: "") }
    var model by remember { mutableStateOf(existing?.model ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    val scope = rememberCoroutineScope()
    val typeLabel = when (type) {
        Catalog.TYPE_VISION -> "识图"
        Catalog.TYPE_EMBED -> "向量"
        else -> "对话"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.card,
        titleContentColor = AppColors.text,
        textContentColor = AppColors.text,
        title = { Text(if (existing == null) "添加云端模型（$typeLabel）" else "编辑云端模型") },
        text = {
            Column {
                OutlinedTextField(
                    name, { name = it }, label = { Text("名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    base, { base = it }, label = { Text("API 地址（Base URL）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    key, { key = it }, label = { Text("密钥（API Key）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    model, { model = it }, label = { Text("模型别名（model）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    notes, { notes = it }, label = { Text("介绍（可选）") }, maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && base.isNotBlank() && key.isNotBlank() && model.isNotBlank()) {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            if (existing == null) {
                                db.addCloudModel(type, name.trim(), base.trim(), key.trim(), model.trim(), notes.trim())
                            } else {
                                db.updateCloudModel(existing.id, name.trim(), base.trim(), key.trim(), model.trim(), notes.trim())
                            }
                        }
                        onSaved()
                    }
                }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
