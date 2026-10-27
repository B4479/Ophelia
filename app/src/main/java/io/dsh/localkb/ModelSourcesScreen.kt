package io.dsh.localkb

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 下载源管理：内置推荐源 + 手动新增/编辑/删除 + 粘贴 JSON 导入。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSourcesScreen(db: Db, onBack: () -> Unit) {
    var sources by remember { mutableStateOf(listOf<ModelSource>()) }
    var editing by remember { mutableStateOf<ModelSource?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        sources = withContext(Dispatchers.IO) { db.listSources() }
    }
    LaunchedEffect(Unit) {
        reload()
        // 首次进入自动加内置推荐源
        if (withContext(Dispatchers.IO) { db.listSources() }.isEmpty()) {
            withContext(Dispatchers.IO) {
                db.addSource("ModelScope 官方镜像", "https://modelscope.cn", true)
                db.addSource("HuggingFace（需网络可达）", "https://huggingface.co", true)
            }
            reload()
        }
    }

    Scaffold(
        containerColor = AppColors.bg,
        topBar = {
            TopAppBar(
                title = { Text("下载源管理", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AppColors.text)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.bg, titleContentColor = AppColors.text)
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 14.dp)) {
            Spacer(Modifier.height(8.dp))
            Text(
                "下载源决定「其它模型」里能看到什么。内置源已收录本机可跑的 7 个模型；你也可以粘贴 JSON 清单导入更多。",
                color = AppColors.muted, fontSize = 11.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { showAdd = true },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent, contentColor = AppColors.onAccent)
                ) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新增源", fontSize = 12.sp)
                }
                OutlinedButton(onClick = { showImport = true }) { Text("粘贴导入", fontSize = 12.sp) }
            }
            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = AppColors.accent, fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sources) { src ->
                    Row(
                        Modifier.fillMaxWidth()
                            .background(AppColors.card, RoundedCornerShape(12.dp))
                            .border(1.dp, AppColors.divider, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Source, null, tint = AppColors.accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(src.name, color = AppColors.text, fontSize = 14.sp)
                            Text(src.url, color = AppColors.muted, fontSize = 10.sp, maxLines = 1)
                        }
                        if (src.builtin) {
                            Text("内置", color = AppColors.muted, fontSize = 10.sp)
                        } else {
                            IconButton(onClick = { editing = src }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Filled.Edit, "编辑", tint = AppColors.muted, modifier = Modifier.size(15.dp))
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { db.deleteSource(src.id) }
                                    reload()
                                }
                            }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Filled.Delete, "删除", tint = AppColors.danger, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showAdd || editing != null) {
        var name by remember(editing) { mutableStateOf(editing?.name ?: "") }
        var url by remember(editing) { mutableStateOf(editing?.url ?: "") }
        AlertDialog(
            onDismissRequest = { showAdd = false; editing = null },
            containerColor = AppColors.card,
            titleContentColor = AppColors.text,
            textContentColor = AppColors.text,
            title = { Text(if (editing == null) "新增下载源" else "编辑下载源") },
            text = {
                Column {
                    OutlinedTextField(
                        name, { name = it }, label = { Text("源名称") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        url, { url = it }, label = { Text("源地址") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank() && url.isNotBlank()) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                if (editing == null) db.addSource(name.trim(), url.trim())
                                else db.updateSource(editing!!.id, name.trim(), url.trim())
                            }
                            showAdd = false; editing = null
                            reload()
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false; editing = null }) { Text("取消") } }
        )
    }

    if (showImport) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImport = false },
            containerColor = AppColors.card,
            titleContentColor = AppColors.text,
            textContentColor = AppColors.text,
            title = { Text("粘贴导入") },
            text = {
                Column {
                    Text("支持两种：① JSON 清单；② ModelScope 仓库地址（如 https://modelscope.cn/models/ggml-org/xxx）", color = AppColors.muted, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        text, { text = it }, minLines = 5, maxLines = 10,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = text.trim()
                    if (t.isNotEmpty()) {
                        scope.launch {
                            val count = withContext(Dispatchers.IO) { importSource(db, t) }
                            message = if (count > 0) "已导入 $count 个模型（见「其它模型」列表）" else "未识别到可用内容"
                            showImport = false
                            reload()
                        }
                    }
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { showImport = false }) { Text("取消") } }
        )
    }
}

/**
 * 解析导入文本：JSON 清单会登记为下载源并写入 settings 备查；
 * 当前版本的「其它模型」来自内置 Catalog，导入内容作为自定义源展示与记录。
 */
private fun importSource(db: Db, text: String): Int {
    return runCatching {
        if (text.startsWith("[") || text.startsWith("{")) {
            val arr = if (text.startsWith("[")) org.json.JSONArray(text) else org.json.JSONArray().put(org.json.JSONObject(text))
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name", o.optString("id", "自定义模型"))
                val url = o.optString("file_url", o.optString("url", ""))
                if (url.isNotBlank()) {
                    db.addSource(name, url, false)
                    n++
                }
            }
            n
        } else {
            // ModelScope 仓库地址
            val repo = text.substringAfter("modelscope.cn/models/", "").trim().trimEnd('/')
            if (repo.isBlank()) 0 else {
                db.addSource("ModelScope · $repo", "https://modelscope.cn/models/$repo", false)
                1
            }
        }
    }.getOrDefault(0)
}
