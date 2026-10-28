package io.dsh.localkb

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val SBg: Color get() = AppColors.bg
private val SCard: Color get() = AppColors.card
private val SAccent: Color get() = AppColors.accent
private val SMuted: Color get() = AppColors.muted

/** 设置中心：模块化入口，后续模块直接往里加。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(db: Db, tts: TtsPlayer, onBack: () -> Unit, initialPage: String = "root") {
    var page by remember { mutableStateOf(initialPage) }

    when (page) {
        "voice" -> VoiceSettingsScreen(db, tts) { page = "root" }
        "model" -> ModelCenterScreen(db) { page = "root" }
        "characters" -> CharacterScreen(db, tts) { page = "root" }
        "profile" -> ProfileScreen(db) { page = "root" }
        "logs" -> DebugLogScreen(db) { page = "root" }
        "theme" -> ThemeScreen(db) { page = "root" }
        "genparams" -> GlobalGenParamsScreen(db) { page = "root" }
        else -> SettingsHome(db, tts, onBack) { page = it }
    }
}

private fun themeSubtitle(): String {
    val fam = when (AppColors.family) {
        AppColors.F_OLIVE -> "奶油橄榄"
        AppColors.F_WARM -> "暖白·暖纸"
        else -> "黑白灰蓝"
    }
    return if (AppColors.follow) "$fam · 跟随系统"
    else fam + " · " + if (AppColors.mode == "dark") "深色" else "浅色"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHome(db: Db, tts: TtsPlayer, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val voiceCount = remember { TtsVoices.list(context).size }
    val modelText = remember {
        val m = db.getSetting("llm_model").orEmpty()
        if (m.isBlank()) "未配置" else m
    }
    val charCount = remember { runCatching { db.listCharacters().size }.getOrDefault(0) }
    val loreCount = remember { runCatching { db.listLorebooks().size }.getOrDefault(0) }

    Scaffold(
        containerColor = SBg,
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AppColors.text)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SBg, titleContentColor = AppColors.text)
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(10.dp))
            ModuleRow(
                icon = Icons.Filled.AccountCircle,
                title = "个人主页",
                subtitle = "昵称：${ProfileStore.displayName(db)}" + if (ProfileStore.isEnabled(db)) "" else "（身份设定已关闭）",
                onClick = { onOpen("profile") }
            )
            Spacer(Modifier.height(10.dp))
            ModuleRow(
                icon = Icons.Filled.RecordVoiceOver,
                title = "音色",
                subtitle = "已导入 ${voiceCount} 个声音包",
                onClick = { onOpen("voice") }
            )
            Spacer(Modifier.height(10.dp))
            ModuleRow(
                icon = Icons.Filled.SmartToy,
                title = "模型选择",
                subtitle = "当前：$modelText",
                onClick = { onOpen("model") }
            )
            Spacer(Modifier.height(10.dp))
            ModuleRow(
                icon = Icons.Filled.Tune,
                title = "生成参数（全局）",
                subtitle = "温度 ${GenParamsStore.global(db).temperature} · 长度 ${GenParamsStore.global(db).maxTokens}",
                onClick = { onOpen("genparams") }
            )
            Spacer(Modifier.height(10.dp))
            ModuleRow(
                icon = Icons.Filled.Palette,
                title = "配色方案",
                subtitle = themeSubtitle(),
                onClick = { onOpen("theme") }
            )
            Spacer(Modifier.height(10.dp))
            ModuleRow(
                icon = Icons.Filled.Person,
                title = "角色卡/世界书",
                subtitle = "$charCount 张角色卡 · $loreCount 个集锦",
                onClick = { onOpen("characters") }
            )

            Spacer(Modifier.height(10.dp))
            ModuleRow(
                icon = Icons.Filled.BugReport,
                title = "调试日志",
                subtitle = "记录消息操作（朗读/复制/重生成/分支/修改/删除）",
                onClick = { onOpen("logs") }
            )

            Spacer(Modifier.height(28.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("更多模块陆续加入…", color = AppColors.muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ModuleRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SCard),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = SAccent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = AppColors.text, fontSize = 16.sp)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = SMuted, fontSize = 12.sp)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = AppColors.muted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSettingsScreen(db: Db, tts: TtsPlayer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var voices by remember { mutableStateOf(TtsVoices.list(context)) }
    var msg by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            val r = withContext(Dispatchers.IO) {
                TtsVoices.importZip(context, uri) { p -> msg = p }
            }
            importing = false
            if (r.isSuccess) {
                voices = TtsVoices.list(context)
                msg = "已导入：${r.getOrNull()?.name}"
            } else {
                msg = "导入失败：${r.exceptionOrNull()?.message}"
            }
        }
    }

    Scaffold(
        containerColor = SBg,
        topBar = {
            TopAppBar(
                title = { Text("音色", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AppColors.text)
                    }
                },
                actions = {
                    TextButton(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !importing) {
                        Text(if (importing) "导入中…" else "导入声音包", color = SAccent, fontSize = 13.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SBg, titleContentColor = AppColors.text)
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {
            // 朗读动作描写（置顶）
            var readActions by remember { mutableStateOf(db.getSetting("tts_read_actions") == "1") }
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.card)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("朗读动作描写", color = AppColors.text, fontSize = 14.sp)
                    Text(
                        if (readActions) "括号和 *…* 里的内容也会读出来" else "跳过（）、()、【】和 *…* 里的动作描写",
                        color = AppColors.muted, fontSize = 11.sp
                    )
                }
                Switch(
                    checked = readActions,
                    onCheckedChange = { v ->
                        readActions = v
                        db.setSetting("tts_read_actions", if (v) "1" else "0")
                    }
                )
            }
            Spacer(Modifier.height(10.dp))
            if (voices.isEmpty()) {
                Text(
                    "还没有声音包。\n点右上角「导入声音包」选一个 *_voice.zip（约 320MB）。",
                    color = SMuted, fontSize = 13.sp
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(voices) { v ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = SCard),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(v.name, color = AppColors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { tts.speak("这是一段语音合成测试。", v) }) {
                                    Text("试听", fontSize = 12.sp, color = SAccent)
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { TtsVoices.delete(v) }
                                        voices = TtsVoices.list(context)
                                    }
                                }) { Icon(Icons.Filled.Delete, "删除", tint = SMuted, modifier = Modifier.size(18.dp)) }
                            }
                        }
                    }
                }
            }
            msg?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = SAccent, fontSize = 12.sp)
            }
            tts.status?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = AppColors.warn, fontSize = 11.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSettingsScreen(db: Db, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var provider by remember { mutableStateOf(db.getSetting("llm_provider") ?: "cloud") }
    var base by remember { mutableStateOf(db.getSetting("llm_base") ?: "https://api.deepseek.com") }
    var vBase by remember { mutableStateOf(db.getSetting("vision_base") ?: Vision.DEFAULT_BASE) }
    var vKey by remember { mutableStateOf(db.getSetting("vision_key") ?: "") }
    var vModel by remember { mutableStateOf(db.getSetting("vision_model") ?: Vision.DEFAULT_MODEL) }
    var vTesting by remember { mutableStateOf(false) }
    var visionLocal by remember { mutableStateOf(db.getSetting("vision_local") == "1") }
    var vlInstalled by remember { mutableStateOf(LocalVision.isInstalled(context)) }
    var vlBusy by remember { mutableStateOf(false) }
    var vlProgress by remember { mutableStateOf("") }
    var vlError by remember { mutableStateOf<String?>(null) }
    var vResult by remember { mutableStateOf<String?>(null) }
    var key by remember { mutableStateOf(db.getSetting("llm_key") ?: "") }
    var model by remember { mutableStateOf(db.getSetting("llm_model") ?: "deepseek-chat") }

    val chatSpec = LocalModels.chat(context)
    var installed by remember { mutableStateOf(LocalModels.isInstalled(context, chatSpec)) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var dlText by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    fun save() {
        db.setSetting("llm_provider", provider)
        db.setSetting("llm_base", base.trim())
        db.setSetting("llm_key", key.trim())
        db.setSetting("llm_model", model.trim())
        testResult = "已保存"
    }

    Scaffold(
        containerColor = SBg,
        topBar = {
            TopAppBar(
                title = { Text("模型选择", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AppColors.text)
                    }
                },
                actions = {
                    TextButton(onClick = { save() }) { Text("保存", color = SAccent, fontSize = 13.sp) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SBg, titleContentColor = AppColors.text)
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // ---------- 本地模型 ----------
            Text("本地模型（手机端）", color = SAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))

            Card(colors = CardDefaults.cardColors(containerColor = SCard), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("向量模型", color = AppColors.text, fontSize = 15.sp)
                    Text(
                        "${LocalModels.EMBEDDING_NAME} · ${LocalModels.EMBEDDING_DIM} 维 · ${LocalModels.EMBEDDING_SIZE} · 已内置",
                        color = SMuted, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(LocalModels.EMBEDDING_NOTE, color = AppColors.muted, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            Card(colors = CardDefaults.cardColors(containerColor = SCard), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("对话模型", color = AppColors.text, fontSize = 15.sp)
                    Text(
                        "${chatSpec.name} · ${chatSpec.sizeLabel} · " + if (installed) "已安装" else "未安装",
                        color = if (installed) AppColors.success else SMuted, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(chatSpec.note, color = AppColors.muted, fontSize = 11.sp)

                    Spacer(Modifier.height(8.dp))
                    if (downloading) {
                        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(dlText ?: "下载中…", color = SAccent, fontSize = 11.sp)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!installed) {
                                OutlinedButton(onClick = {
                                    downloading = true
                                    progress = 0f
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) {
                                            LocalModels.download(context, chatSpec) { done, total ->
                                                if (total > 0) {
                                                    progress = (done.toFloat() / total).coerceIn(0f, 1f)
                                                    dlText = "已下载 ${done / 1024 / 1024}MB / ${total / 1024 / 1024}MB"
                                                }
                                            }
                                        }
                                        downloading = false
                                        r.onSuccess {
                                            installed = true
                                            dlText = null
                                        }.onFailure { dlText = "下载失败：${it.message}" }
                                    }
                                }) { Text("下载并安装", color = SAccent, fontSize = 13.sp) }
                            } else {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { LocalModels.delete(context, chatSpec) }
                                        installed = false
                                    }
                                }) { Text("删除", color = AppColors.danger, fontSize = 13.sp) }
                            }
                        }
                        dlText?.let { Text(it, color = SMuted, fontSize = 11.sp) }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---------- 对话模型来源 ----------
            Text("对话模型来源", color = SAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = provider == "local", onClick = {
                    provider = "local"
                    db.setSetting("llm_provider", "local")
                })
                Text("本地（${chatSpec.name}）", color = AppColors.text, fontSize = 14.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = provider == "cloud", onClick = {
                    provider = "cloud"
                    db.setSetting("llm_provider", "cloud")
                })
                Text("云端（下方配置）", color = AppColors.text, fontSize = 14.sp)
            }
            if (provider == "local" && !installed) {
                Text("本地模型还没下载，先点上面的「下载并安装」", color = AppColors.warn, fontSize = 11.sp)
            }

            Spacer(Modifier.height(14.dp))

            // ---------- 本地识图 ----------
            Text("本地识图（离线）", color = SAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                "Qwen2-VL-2B + 视觉编码器，共约 1.7 GB；装好后断网也能看图/识字",
                color = AppColors.muted, fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = visionLocal,
                    onCheckedChange = {
                        visionLocal = it
                        db.setSetting("vision_local", if (it) "1" else "0")
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text("图片优先用本地识图", color = AppColors.text, fontSize = 13.sp)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!vlInstalled) {
                    OutlinedButton(
                        enabled = !vlBusy,
                        onClick = {
                            vlBusy = true
                            vlError = null
                            scope.launch {
                                val r1 = withContext(Dispatchers.IO) {
                                    LocalModels.download(context, LocalModels.VISION) { d, t ->
                                        vlProgress = "主模型 ${d / 1048576}/${t / 1048576} MB"
                                    }
                                }
                                if (r1.isFailure) {
                                    vlError = "主模型下载失败：${r1.exceptionOrNull()?.message}"
                                    vlBusy = false
                                    return@launch
                                }
                                val r2 = withContext(Dispatchers.IO) {
                                    LocalModels.download(context, LocalModels.VISION_MMPROJ) { d, t ->
                                        vlProgress = "视觉编码器 ${d / 1048576}/${t / 1048576} MB"
                                    }
                                }
                                if (r2.isFailure) {
                                    vlError = "视觉编码器下载失败：${r2.exceptionOrNull()?.message}"
                                } else {
                                    vlInstalled = LocalVision.isInstalled(context)
                                    vlProgress = ""
                                }
                                vlBusy = false
                            }
                        }
                    ) { Text(if (vlBusy) "下载中…" else "下载并安装（1.7 GB）") }
                } else {
                    Text("已安装（本地识图可用）", color = AppColors.success, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        enabled = !vlBusy,
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    LocalVision.unload()
                                    LocalModels.delete(context, LocalModels.VISION)
                                    LocalModels.delete(context, LocalModels.VISION_MMPROJ)
                                }
                                vlInstalled = false
                            }
                        }
                    ) { Text("删除", color = AppColors.danger) }
                }
            }
            if (vlBusy && vlProgress.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(vlProgress, color = AppColors.muted, fontSize = 11.sp)
            }
            vlError?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = AppColors.warn, fontSize = 11.sp)
            }

            Spacer(Modifier.height(14.dp))

            // ---------- 云端模型 ----------
            Text("云端模型（OpenAI 兼容）", color = SAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {
                    base = "https://api.deepseek.com"; model = "deepseek-chat"
                }, label = { Text("DeepSeek", fontSize = 12.sp) })
                AssistChip(onClick = {
                    base = "https://api.siliconflow.cn/v1"; model = "Qwen/Qwen2.5-7B-Instruct"
                }, label = { Text("硅基流动", fontSize = 12.sp) })
                AssistChip(onClick = {
                    base = "https://api.openai.com/v1"; model = "gpt-4o-mini"
                }, label = { Text("OpenAI", fontSize = 12.sp) })
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(base, { base = it }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(key, { key = it }, label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(model, { model = it }, label = { Text("模型名") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                enabled = !testing,
                onClick = {
                    testing = true
                    testResult = null
                    scope.launch {
                        testResult = withContext(Dispatchers.IO) { testConnection(base.trim(), key.trim()) }
                        testing = false
                    }
                }
            ) { Text(if (testing) "测试中…" else "测试连接") }
            testResult?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = if (it.startsWith("连接成功") || it == "已保存") AppColors.success else AppColors.warn, fontSize = 12.sp)
            }

            Spacer(Modifier.height(18.dp))

            // ---------- 云端识图 ----------
            Text("云端识图（OpenAI 兼容多模态）", color = SAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(
                "用于聊天里发送图片时理解画面/提取文字；DeepSeek 本身不支持图片，需要单独的视觉模型。",
                color = AppColors.muted, fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                vBase, {
                    vBase = it
                    db.setSetting("vision_base", it.trim())
                },
                label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                vKey, {
                    vKey = it
                    db.setSetting("vision_key", it.trim())
                },
                label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                vModel, {
                    vModel = it
                    db.setSetting("vision_model", it.trim())
                },
                label = { Text("模型名") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                enabled = !vTesting,
                onClick = {
                    vTesting = true
                    vResult = null
                    scope.launch {
                        vResult = withContext(Dispatchers.IO) {
                            val cfg = Vision.Config(
                                vBase.trim().trimEnd('/'),
                                vKey.trim(),
                                vModel.trim()
                            )
                            if (cfg.apiKey.isBlank()) {
                                "请先填 API Key"
                            } else {
                                Vision.describe(
                                    cfg,
                                    android.util.Base64.encodeToString(
                                        java.io.ByteArrayOutputStream().also { bos ->
                                            android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
                                                .apply { eraseColor(android.graphics.Color.RED) }
                                                .compress(android.graphics.Bitmap.CompressFormat.PNG, 90, bos)
                                        }.toByteArray(),
                                        android.util.Base64.NO_WRAP
                                    ),
                                    "image/png",
                                    "只回复两个字：可用"
                                )
                                    .fold(
                                        onSuccess = { "连接成功：${it.trim().take(20)}" },
                                        onFailure = { "失败：${it.message?.take(120)}" }
                                    )
                            }
                        }
                        vTesting = false
                    }
                }
            ) { Text(if (vTesting) "测试中…" else "测试识图") }
            vResult?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    color = if (it.startsWith("连接成功")) AppColors.success else AppColors.warn,
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "说明：本地向量模型负责知识库检索（不可关闭）；对话可以选本地 Qwen 或云端，随时切换。",
                color = AppColors.muted, fontSize = 11.sp
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun testConnection(base: String, key: String): String {
    if (base.isBlank() || key.isBlank()) return "请先填写 Base URL 和 API Key"
    return runCatching {
        val client = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
        val url = base.trimEnd('/') + "/models"
        val req = Request.Builder().url(url).addHeader("Authorization", "Bearer $key").get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                val count = runCatching {
                    org.json.JSONObject(body).getJSONArray("data").length()
                }.getOrDefault(0)
                "连接成功，可用模型 $count 个"
            } else {
                "连接失败 HTTP ${resp.code}：${body.take(160)}"
            }
        }
    }.getOrElse { "连接失败：${it.message}" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalGenParamsScreen(db: Db, onBack: () -> Unit) {
    var p by remember { mutableStateOf(GenParamsStore.global(db)) }
    fun save(next: GenParams) {
        p = next
        GenParamsStore.saveGlobal(db, next)
    }
    Scaffold(
        containerColor = SBg,
        topBar = {
            TopAppBar(
                title = { Text("生成参数（全局）", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AppColors.text)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SBg, titleContentColor = AppColors.text)
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "所有“跟随全局”的会话都用这套参数；单个会话可在对话顶栏的滑杆里单独覆盖。",
                color = AppColors.muted, fontSize = 11.sp
            )
            Spacer(Modifier.height(10.dp))
            SliderRow("温度", p.temperature.toFloat(), 0f..1.5f, String.format("%.2f", p.temperature)) {
                save(p.copy(temperature = it.toDouble()))
            }
            SliderRow("top_p", p.topP.toFloat(), 0.1f..1f, String.format("%.2f", p.topP)) {
                save(p.copy(topP = it.toDouble()))
            }
            SliderRow("top_k", p.topK.toFloat(), 0f..100f, "${p.topK}") {
                save(p.copy(topK = it.toInt()))
            }
            Text("top_k 仅本地模型生效", color = AppColors.muted, fontSize = 10.sp)
            SliderRow("最大输出", p.maxTokens.toFloat(), 64f..2048f, "${p.maxTokens}") {
                save(p.copy(maxTokens = (it / 16).toInt() * 16))
            }
            SliderRow("重复惩罚", p.repPenalty.toFloat(), 1f..1.5f, String.format("%.2f", p.repPenalty)) {
                save(p.copy(repPenalty = it.toDouble()))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = p.stop.joinToString("\n"),
                onValueChange = { text ->
                    save(p.copy(stop = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }))
                },
                label = { Text("停止词（每行一个）") },
                minLines = 2, maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = { save(GenParams()) }) {
                Text("恢复默认", color = AppColors.muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, show: String, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AppColors.text, fontSize = 13.sp, modifier = Modifier.width(74.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(show, color = AppColors.muted, fontSize = 12.sp, modifier = Modifier.width(52.dp))
    }
}
