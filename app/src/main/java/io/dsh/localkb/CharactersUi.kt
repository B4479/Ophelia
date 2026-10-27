package io.dsh.localkb

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private val CBg: Color get() = AppColors.bg
private val CCard: Color get() = AppColors.card
private val CAccent: Color get() = AppColors.accent
private val CMuted: Color get() = AppColors.muted

/** 名字 → 声音包目录（导入时自动匹配，可在编辑弹窗里改）。 */
private val VOICE_BY_NAME = mapOf(
    "乐奈" to "rana", "要乐奈" to "rana",
    "爱音" to "anon", "千早爱音" to "anon",
    "祥子" to "xiangzi", "丰川祥子" to "xiangzi",
    "素世" to "sushi", "长崎素世" to "sushi",
    "仁菜" to "rencai", "井芹仁菜" to "rencai",
    "安和卯" to "anhemao", "安和昴" to "anhemao",
    "普瑞赛斯" to "pres",
    "洛琪希" to "roxy",
    "八奈见杏菜" to "banaijianxingcai",
    "雪乃" to "yukino_s",
)

private fun saveAvatar(context: Context, bytes: ByteArray): String? = runCatching {
    if (!CardParser.isPng(bytes)) return null
    val dir = File(context.filesDir, "avatars")
    if (!dir.exists()) dir.mkdirs()
    val f = File(dir, "card_${System.currentTimeMillis()}.png")
    f.writeBytes(bytes)
    f.absolutePath
}.getOrNull()

private fun saveAvatarFromUri(context: Context, uri: Uri): String? = runCatching {
    val dir = File(context.filesDir, "avatars")
    if (!dir.exists()) dir.mkdirs()
    val f = File(dir, "avatar_${System.currentTimeMillis()}.jpg")
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input)
        f.outputStream().use { input.copyTo(it) }
    }
    f.absolutePath
}.getOrNull()

private fun autoVoiceFor(name: String): String? =
    VOICE_BY_NAME.entries.firstOrNull { name.contains(it.key) || it.key.contains(name) }?.value

/** 编辑弹窗的表单状态。id == null 表示新建。 */
private data class EditorState(
    val id: Long?,
    val name: String = "",
    val systemPrompt: String = "",
    val notes: String = "",
    val scenario: String = "",
    val description: String = "",
    val firstMes: String = "",
    val avatarPath: String? = null,
    val voiceId: String? = null,
    val rawJson: String? = null,
)

private data class PendingImport(
    val card: CharacterCard,
    val avatarPath: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterScreen(db: Db, tts: TtsPlayer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var rows by remember { mutableStateOf(listOf<CharacterRow>()) }
    var editor by remember { mutableStateOf<EditorState?>(null) }
    var pending by remember { mutableStateOf<PendingImport?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        rows = withContext(Dispatchers.IO) { db.listCharacters() }
    }
    LaunchedEffect(Unit) { reload() }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val card = CardParser.parse(bytes) ?: error("无法识别（只支持 V2 PNG 或 JSON 角色卡）")
                    PendingImport(card, saveAvatar(context, bytes))
                }
            }
            r.onSuccess { pending = it }
                .onFailure { msg = "导入失败：${it.message}" }
        }
    }

    Scaffold(
        containerColor = CBg,
        topBar = {
            TopAppBar(
                title = { Text("角色卡", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AppColors.text) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CBg, titleContentColor = AppColors.text)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddMenu = true }, containerColor = CAccent) {
                Icon(Icons.Filled.Add, "添加角色卡", tint = AppColors.onAccent)
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 14.dp)) {
            msg?.let {
                Text(it, color = CAccent, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
            }
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "还没有角色卡。\n点右下角 + 导入或从零新建。",
                        color = CMuted, fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(rows) { row ->
                        CharacterRowItem(row) {
                            val card = CardParser.fromJson(row.cardJson, null)
                            val notes = runCatching {
                                JSONObject(row.cardJson).optJSONObject("data")?.optString("creator_notes").orEmpty()
                            }.getOrNull().orEmpty()
                            editor = EditorState(
                                id = row.id,
                                name = row.name,
                                systemPrompt = card?.systemPrompt.orEmpty(),
                                notes = notes,
                                scenario = card?.scenario.orEmpty(),
                                description = card?.description.orEmpty(),
                                firstMes = card?.firstMes.orEmpty(),
                                avatarPath = row.avatar,
                                voiceId = row.voiceId,
                                rawJson = row.cardJson,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddMenu) {
        ModalBottomSheet(onDismissRequest = { showAddMenu = false }, containerColor = CCard) {
            Column(Modifier.padding(bottom = 24.dp)) {
                ListItem(
                    headlineContent = { Text("导入角色卡", color = AppColors.text) },
                    supportingContent = { Text("支持 SillyTavern V2 的 PNG / JSON", color = CMuted, fontSize = 12.sp) },
                    colors = ListItemDefaults.colors(containerColor = CCard),
                    modifier = Modifier.clickable {
                        showAddMenu = false
                        importer.launch(arrayOf("image/png", "application/json", "text/plain", "*/*"))
                    }
                )
                ListItem(
                    headlineContent = { Text("从零新建", color = AppColors.text) },
                    supportingContent = { Text("手写人设与系统提示词", color = CMuted, fontSize = 12.sp) },
                    colors = ListItemDefaults.colors(containerColor = CCard),
                    modifier = Modifier.clickable {
                        showAddMenu = false
                        editor = EditorState(id = null)
                    }
                )
            }
        }
    }

    pending?.let { p ->
        ImportPreviewDialog(
            pending = p,
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        db.addCharacter(p.card.copy(avatarPath = p.avatarPath), autoVoiceFor(p.card.name))
                    }
                    pending = null
                    msg = "已导入：${p.card.name}"
                    reload()
                }
            },
            onDismiss = {
                p.avatarPath?.let { runCatching { File(it).delete() } }
                pending = null
            }
        )
    }

    editor?.let { st ->
        val voices = remember { TtsVoices.list(context) }
        CardEditorDialog(
            state = st,
            voices = voices,
            onDismiss = { editor = null },
            onSave = { newState ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        if (newState.id == null) {
                            val json = CardParser.buildJson(
                                name = newState.name,
                                systemPrompt = newState.systemPrompt,
                                notes = newState.notes,
                                scenario = newState.scenario,
                                description = newState.description,
                                firstMes = newState.firstMes,
                            )
                            val card = CardParser.fromJson(json, null)!!
                            db.addCharacter(
                                card.copy(avatarPath = newState.avatarPath),
                                newState.voiceId ?: autoVoiceFor(newState.name)
                            )
                        } else {
                            val base = CardParser.fromJson(newState.rawJson ?: "{}", null)
                            val merged = if (base != null) {
                                CardParser.withEdits(
                                    base,
                                    mapOf(
                                        "name" to newState.name,
                                        "system_prompt" to newState.systemPrompt,
                                        "creator_notes" to newState.notes,
                                        "scenario" to newState.scenario,
                                        "description" to newState.description,
                                        "first_mes" to newState.firstMes,
                                    )
                                )
                            } else newState.rawJson ?: "{}"
                            db.updateCharacterFull(
                                newState.id, newState.name, merged, newState.voiceId, newState.avatarPath
                            )
                        }
                    }
                    editor = null
                    msg = if (newState.id == null) "已新建：${newState.name}" else "已保存：${newState.name}"
                    reload()
                }
            },
            onDelete = { st2 ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        st2.id?.let { db.deleteCharacter(it) }
                        st2.avatarPath?.let { runCatching { File(it).delete() } }
                    }
                    editor = null
                    msg = "已删除"
                    reload()
                }
            }
        )
    }
}

@Composable
private fun CharacterRowItem(row: CharacterRow, onEdit: () -> Unit) {
    val card = remember(row.id, row.cardJson) { CardParser.fromJson(row.cardJson, null) }
    Card(
        colors = CardDefaults.cardColors(containerColor = CCard),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(row.avatar, 52.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.name, color = AppColors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                val desc = card?.description.orEmpty().replace("\n", " ")
                if (desc.isNotBlank()) {
                    Text(desc, color = CMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val v = row.voiceId
                Text(
                    if (v.isNullOrBlank()) "未绑定音色" else "音色：$v",
                    color = if (v.isNullOrBlank()) AppColors.warn else CAccent,
                    fontSize = 11.sp
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, "编辑", tint = CAccent)
            }
        }
    }
}

@Composable
private fun Avatar(path: String?, size: androidx.compose.ui.unit.Dp) {
    val bmp = remember(path) {
        path?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
    }
    Box(
        Modifier.size(size).clip(if (bmp == null) RoundedCornerShape(10.dp) else CircleShape)
            .background(AppColors.cardAlt),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(Icons.Filled.Person, null, tint = CMuted)
        }
    }
}

/** 导入预览：头像 + 名称 + 系统提示词预览，确认后才入库。 */
@Composable
private fun ImportPreviewDialog(pending: PendingImport, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val card = pending.card
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入预览") },
        text = {
            Column {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Avatar(pending.avatarPath, 84.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(card.name, color = AppColors.text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    if (card.tags.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(card.tags.joinToString(" / ") { "#$it" }, color = CMuted, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("系统提示词", color = CAccent, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                val preview = when {
                    card.systemPrompt.isNotBlank() -> card.systemPrompt
                    card.description.isNotBlank() -> "（未提供 system_prompt，将使用角色设定）\n\n" + card.description
                    else -> "（无）"
                }
                Box(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    Text(preview, color = AppColors.text, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.success,
                    contentColor = AppColors.text
                )
            ) { Text("导入确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = CMuted) } }
    )
}

/** 大号编辑弹窗：新建与编辑共用，编辑模式底部多一个删除键。 */
@Composable
private fun CardEditorDialog(
    state: EditorState,
    voices: List<TtsVoice>,
    onDismiss: () -> Unit,
    onSave: (EditorState) -> Unit,
    onDelete: (EditorState) -> Unit,
) {
    val context = LocalContext.current

    var name by remember { mutableStateOf(state.name) }
    var systemPrompt by remember { mutableStateOf(state.systemPrompt) }
    var notes by remember { mutableStateOf(state.notes) }
    var scenario by remember { mutableStateOf(state.scenario) }
    var description by remember { mutableStateOf(state.description) }
    var firstMes by remember { mutableStateOf(state.firstMes) }
    var avatarPath by remember { mutableStateOf(state.avatarPath) }
    var voiceId by remember { mutableStateOf(state.voiceId) }
    var moreOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            avatarPath = saveAvatarFromUri(context, uri)
        }
    }

    fun current(): EditorState = state.copy(
        name = name.trim(),
        systemPrompt = systemPrompt,
        notes = notes,
        scenario = scenario,
        description = description,
        firstMes = firstMes,
        avatarPath = avatarPath,
        voiceId = voiceId,
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        androidx.compose.runtime.key(state.id ?: -1L) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = CCard,
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f)
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (state.id == null) "新建角色卡" else "编辑角色卡",
                        color = AppColors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { if (name.isNotBlank()) onSave(current()) },
                        enabled = name.isNotBlank()
                    ) { Text("保存", color = if (name.isNotBlank()) CAccent else CMuted) }
                    TextButton(onClick = onDismiss) { Text("取消", color = CMuted) }
                }

                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(Modifier.clickable { pickAvatar.launch("image/*") }) {
                            Avatar(avatarPath, 84.dp)
                        }
                        TextButton(onClick = { pickAvatar.launch("image/*") }) {
                            Text("上传图片", fontSize = 12.sp, color = CAccent)
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("角色名称（必填）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { systemPrompt = it },
                        label = { Text("系统提示词") },
                        placeholder = { Text("角色设定、语气、规则……可换行", color = CMuted) },
                        minLines = 8,
                        maxLines = 16,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("备注（可选，只给自己看）") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { moreOpen = !moreOpen }) {
                        Text(
                            if (moreOpen) "收起更多设定 ▲" else "更多设定（世界背景 / 角色设定 / 开场白）▼",
                            fontSize = 12.sp, color = CAccent
                        )
                    }
                    if (moreOpen) {
                        OutlinedTextField(
                            value = scenario, onValueChange = { scenario = it },
                            label = { Text("世界背景") }, minLines = 2, maxLines = 6,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = description, onValueChange = { description = it },
                            label = { Text("角色设定 / 简介") }, minLines = 2, maxLines = 6,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = firstMes, onValueChange = { firstMes = it },
                            label = { Text("开场白") }, minLines = 2, maxLines = 6,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("朗读音色", color = CAccent, fontSize = 12.sp)
                        voices.forEach { v ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = voiceId == v.id, onClick = { voiceId = v.id })
                                Text(v.name, color = AppColors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    if (state.id != null) {
                        HorizontalDivider(color = AppColors.cardAlt)
                        Spacer(Modifier.height(12.dp))
                        if (!confirmDelete) {
                            TextButton(onClick = { confirmDelete = true }) {
                                Text("删除这个角色卡", color = AppColors.danger, fontSize = 13.sp)
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("确认删除？", color = AppColors.danger, fontSize = 13.sp)
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { onDelete(current()) }) { Text("确认", color = AppColors.danger) }
                                TextButton(onClick = { confirmDelete = false }) { Text("取消", color = CMuted) }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        }
    }
}
