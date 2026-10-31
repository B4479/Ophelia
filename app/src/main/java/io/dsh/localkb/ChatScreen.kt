package io.dsh.localkb

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ChBg: Color get() = AppColors.bg
private val ChCard: Color get() = AppColors.card
private val ChAccent: Color get() = AppColors.accent
private val ChMuted: Color get() = AppColors.muted
private val ChUser: Color get() = AppColors.user
private val ChDrawer: Color get() = AppColors.drawer

const val MODE_CHAT = "chat"
const val MODE_WORK = "work"

/** 左上角「//」菜单图标（豆包风格）。 */
@Composable
private fun SlashMenuIcon(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Canvas(Modifier.size(22.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 2.6.dp.toPx()
            drawLine(AppColors.text, Offset(w * 0.62f, h * 0.08f), Offset(w * 0.28f, h * 0.92f), stroke, StrokeCap.Round)
            drawLine(AppColors.text, Offset(w * 0.94f, h * 0.08f), Offset(w * 0.60f, h * 0.92f), stroke, StrokeCap.Round)
        }
    }
}

private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

private fun nowTitle(prefix: String): String {
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
    return "$prefix · ${fmt.format(java.util.Date())}"
}

@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    ExperimentalFoundationApi::class
)
@Composable
fun ChatScreen(
    db: Db,
    tts: TtsPlayer,
    amb: AmbienceEngine,
    onOpenSettings: (String) -> Unit,
    onOpenCitation: (docId: Long, page: Int) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chatBg by remember { mutableStateOf<String?>(null) }
    var bgBlur by remember { mutableStateOf(8f) }
    var bgDim by remember { mutableStateOf(60f) }
    var showBgDialog by remember { mutableStateOf(false) }
    var showLorePicker by remember { mutableStateOf(false) }
    var assistantAvatarPath by remember { mutableStateOf<String?>(null) }
    var pendingImage by remember { mutableStateOf<String?>(null) }
    var pendingImageDesc by remember { mutableStateOf<String?>(null) }
    var pendingDocPath by remember { mutableStateOf<String?>(null) }
    var pendingDocName by remember { mutableStateOf<String?>(null) }
    var pendingDocChars by remember { mutableStateOf(0) }
    var pendingDocFull by remember { mutableStateOf(0) }
    var pendingDocText by remember { mutableStateOf<String?>(null) }
    var docBusy by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var visionBusy by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var notice by remember { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val path = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = java.io.File(context.filesDir, "attachments").apply { mkdirs() }
                        val f = java.io.File(dir, "img_${System.currentTimeMillis()}.jpg")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            f.outputStream().use { out -> input.copyTo(out) }
                        }
                        f.absolutePath
                    }.getOrNull()
                }
                if (path != null) {
                    pendingImage = path
                    pendingImageDesc = null
                    pendingDocPath = null
                    pendingDocName = null
                    pendingDocText = null
                }
            }
        }
    }

    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                docBusy = true
                val picked = withContext(Dispatchers.IO) {
                    runCatching {
                        val name = queryDisplayName(context, uri) ?: "未命名文件"
                        val dir = java.io.File(context.filesDir, "attachments").apply { mkdirs() }
                        val safe = name.replace(Regex("[^\\w\u4e00-\\u9fff.\\-]"), "_").take(60)
                        val dest = java.io.File(dir, "doc_${System.currentTimeMillis()}_$safe")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            dest.outputStream().use { out -> input.copyTo(out) }
                        }
                        val kind = Extract.kindOf(name)
                        val pages = Extract.extract(context, dest, kind)
                        val full = pages.joinToString("\n\n") { it.second }.trim()
                        Triple(dest.absolutePath, name, full)
                    }.getOrNull()
                }
                if (picked != null) {
                    val (path, name, full) = picked
                    pendingImage = null
                    pendingDocPath = path
                    pendingDocName = name
                    pendingDocFull = full.length
                    pendingDocText = full.take(8000)
                    pendingDocChars = minOf(full.length, 8000)
                } else {
                    notice = "无法读取这个文件（格式不支持或损坏）"
                }
                docBusy = false
            }
        }
    }

    val assistantAvatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val path = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = java.io.File(context.filesDir, "avatars").apply { mkdirs() }
                        val f = java.io.File(dir, "assistant_${System.currentTimeMillis()}.jpg")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            f.outputStream().use { out -> input.copyTo(out) }
                        }
                        f.absolutePath
                    }.getOrNull()
                }
                if (path != null) assistantAvatarPath = path
            }
        }
    }

    var chatsChat by remember { mutableStateOf(listOf<ChatRow>()) }
    var chatsWork by remember { mutableStateOf(listOf<ChatRow>()) }
    var current by remember { mutableStateOf<ChatRow?>(null) }
    var messages by remember { mutableStateOf(listOf<MessageRow>()) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var streamingText by remember { mutableStateOf<String?>(null) }
    var streamingModel by remember { mutableStateOf<String?>(null) }
    var canceller by remember { mutableStateOf<StreamCanceller?>(null) }
    var lastCitations by remember { mutableStateOf<String?>(null) }
    var lastHitCount by remember { mutableStateOf(0) }
    var lastTopScore by remember { mutableStateOf(0f) }
    var lastTopK by remember { mutableStateOf(5) }
    var hintText by remember { mutableStateOf<String?>(null) }
    var hintMsgId by remember { mutableStateOf<Long?>(null) }
    var renameTarget by remember { mutableStateOf<ChatRow?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showNewChatMenu by remember { mutableStateOf(false) }
    var newChatLorebookId by remember { mutableStateOf<Long?>(null) }
    var newChatLoreName by remember { mutableStateOf<String?>(null) }
    var showNewChatLorePicker by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(db.getSetting("onboarding_done") == "0") }
    var showGenPanel by remember { mutableStateOf(false) }
    var genParams by remember { mutableStateOf(GenParams()) }
    var showMemoryDialog by remember { mutableStateOf(false) }
    var memSummary by remember { mutableStateOf<ChatSummary?>(null) }
    var memCount by remember { mutableStateOf(0) }
    var memBusy by remember { mutableStateOf(false) }
    var currentLoreName by remember { mutableStateOf<String?>(null) }
    var currentLoreIds by remember { mutableStateOf(listOf<Long>()) }
    var currentLoreNames by remember { mutableStateOf(listOf<String>()) }
    var newChatLoreIds by remember { mutableStateOf(listOf<Long>()) }
    var newChatLoreNames by remember { mutableStateOf(listOf<String>()) }
    var showCharPicker by remember { mutableStateOf(false) }
    var showNewWorkMenu by remember { mutableStateOf(false) }
    var showNewKbPicker by remember { mutableStateOf(false) }
    var showChatKbPicker by remember { mutableStateOf(false) }
    var showAssistantEditor by remember { mutableStateOf(false) }
    var showVoicePicker by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var characters by remember { mutableStateOf(listOf<CharacterRow>()) }
    var allKbs by remember { mutableStateOf(listOf<Kb>()) }
    var newWorkKbs by remember { mutableStateOf(listOf<Long>()) }
    var newAssistantName by remember { mutableStateOf("") }
    var newAssistantNote by remember { mutableStateOf("") }
    var newAssistantPrompt by remember { mutableStateOf("") }
    var assistantForNew by remember { mutableStateOf(false) }
    var pickKbs by remember { mutableStateOf(setOf<Long>()) }
    var newChatTitle by remember { mutableStateOf("") }
    var newWorkTitle by remember { mutableStateOf("") }
    var meAvatar by remember { mutableStateOf<String?>(null) }
    var aiAvatar by remember { mutableStateOf<String?>(null) }
    val localCancel = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }
    val textExpanded = remember { mutableStateMapOf<Long, Boolean>() }

    var deleteTarget by remember { mutableStateOf<MessageRow?>(null) }
    var modelPickerFor by remember { mutableStateOf<MessageRow?>(null) }
    var editTarget by remember { mutableStateOf<MessageRow?>(null) }

    val listState = rememberLazyListState()

    suspend fun reloadLists() {
        withContext(Dispatchers.IO) {
            chatsChat = db.listChats(MODE_CHAT)
            chatsWork = db.listChats(MODE_WORK)
        }
    }

    /** 没有发过任何消息的会话不留档：切走/退出时删除。 */
    fun discardIfEmpty(row: ChatRow?, exceptId: Long? = null) {
        val r = row ?: return
        if (r.id == exceptId) return
        scope.launch {
            val empty = withContext(Dispatchers.IO) { db.messageCount(r.id) == 0 }
            if (empty) {
                withContext(Dispatchers.IO) {
                    db.deleteChat(r.id)
                    if (db.getSetting("last_chat_id") == r.id.toString()) {
                        db.setSetting("last_chat_id", "")
                    }
                }
                reloadLists()
            }
        }
    }

    suspend fun openChat(row: ChatRow) {
        val prev = current
        if (prev != null && prev.id != row.id) discardIfEmpty(prev, row.id)
        current = row
        withContext(Dispatchers.IO) { db.setSetting("last_chat_id", row.id.toString()) }
        messages = withContext(Dispatchers.IO) { db.messages(row.id) }
        genParams = withContext(Dispatchers.IO) { GenParamsStore.effective(db, row.id, row.mode) }
        val bound = withContext(Dispatchers.IO) {
            runCatching { db.chatLoreIds(row.id) }.getOrDefault(emptyList())
        }
        currentLoreIds = bound
        currentLoreNames = withContext(Dispatchers.IO) {
            bound.mapNotNull { id -> runCatching { db.lorebookById(id)?.name }.getOrNull() }
        }
        currentLoreName = currentLoreNames.firstOrNull()
        chatBg = row.bgPath?.takeIf { it.isNotBlank() }
        bgBlur = row.bgBlur.toFloat()
        bgDim = row.bgDim.toFloat()
        expanded.clear()
    }

    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val path = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = java.io.File(context.filesDir, "backgrounds").apply { mkdirs() }
                        val f = java.io.File(dir, "chat_bg_${System.currentTimeMillis()}.jpg")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            f.outputStream().use { out -> input.copyTo(out) }
                        }
                        val row = current
                        if (row == null) return@runCatching null
                        row.bgPath?.let { old -> runCatching { java.io.File(old).delete() } }
                        db.updateChatBg(row.id, f.absolutePath, bgBlur.toInt(), bgDim.toInt())
                        f.absolutePath
                    }.getOrNull()
                }
                if (path != null) chatBg = path
            }
        }
    }


    /** 会话引用的知识库 id 列表。 */
    fun kbIdsOf(row: ChatRow): List<Long> {
        val raw = row.kbs
        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = org.json.JSONArray(raw)
                return (0 until arr.length()).map { arr.getLong(it) }
            }
        }
        return row.kbId?.let { listOf(it) } ?: emptyList()
    }

    suspend fun reloadMessages() {
        current?.let { messages = withContext(Dispatchers.IO) { db.messages(it.id) } }
    }

    LaunchedEffect(Unit) {
        reloadLists()
        // 恢复上次浏览的会话；不存在（被删/被清理）就停在空对话页
        val lastId = withContext(Dispatchers.IO) { db.getSetting("last_chat_id")?.toLongOrNull() }
        val restored = lastId?.let { id -> withContext(Dispatchers.IO) { db.chatById(id) } }
        if (restored != null) {
            openChat(restored)
        } else {
            current = null
            messages = emptyList()
        }
    }

    LaunchedEffect(showCharPicker) {
        if (showCharPicker) {
            characters = withContext(Dispatchers.IO) { db.listCharacters() }
        }
    }

    LaunchedEffect(showNewKbPicker, showChatKbPicker) {
        if (showNewKbPicker || showChatKbPicker) {
            allKbs = withContext(Dispatchers.IO) { db.listKbs() }
            pickKbs = if (showChatKbPicker) current?.let { kbIdsOf(it).toSet() } ?: emptySet() else newWorkKbs.toSet()
        }
    }

    LaunchedEffect(current?.id, current?.characterId, current?.assistantAvatar) {
        meAvatar = withContext(Dispatchers.IO) { db.getSetting(ProfileStore.KEY_AVATAR) }
        aiAvatar = withContext(Dispatchers.IO) {
            // 优先角色卡头像；工作任务用工作助理头像；都没有则显示默认「AI」
            val charAvatar = current?.characterId?.let { cid ->
                runCatching { db.listCharacters().firstOrNull { it.id == cid }?.avatar }.getOrNull()
            }
            charAvatar ?: current?.assistantAvatar
        }
    }

    LaunchedEffect(hintText, hintMsgId) {
        if (hintText != null) {
            kotlinx.coroutines.delay(3000)
            hintText = null
            hintMsgId = null
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun createChatSession(characterId: Long?, customTitle: String = "") {
        val loreIds = newChatLoreIds
        scope.launch {
            val title = customTitle.ifBlank { nowTitle("新对话") }
            val id = withContext(Dispatchers.IO) {
                db.createChat(MODE_CHAT, title, characterId, null, lorebookIds = loreIds)
            }
            newChatLorebookId = null
            newChatLoreName = null
            newChatLoreIds = emptyList()
            newChatLoreNames = emptyList()
            val row = withContext(Dispatchers.IO) { db.chatById(id) } ?: return@launch
            reloadLists()
            openChat(row)
            drawerState.close()
            notice = if (characterId == null) null else null
        }
    }

    fun createWorkSession(
        kbs: List<Long>,
        assistantName: String,
        assistantNote: String,
        assistantPrompt: String,
        assistantAvatar: String? = null,
        customTitle: String = "",
    ) {
        scope.launch {
            val title = customTitle.ifBlank { nowTitle("工作") }
            val kbsJson = org.json.JSONArray(kbs).toString()
            val id = withContext(Dispatchers.IO) {
                db.createChat(
                    MODE_WORK, title, null, kbs.firstOrNull(),
                    kbs = if (kbs.isEmpty()) null else kbsJson,
                    assistantName = assistantName.ifBlank { null },
                    assistantNote = assistantNote.ifBlank { null },
                    assistantPrompt = assistantPrompt.ifBlank { null },
                    assistantAvatar = assistantAvatar,
                )
            }
            val row = withContext(Dispatchers.IO) { db.chatById(id) } ?: return@launch
            reloadLists()
            openChat(row)
            drawerState.close()
            notice = if (kbs.isEmpty()) "未引用知识库，将直接对话" else null
        }
    }

    fun voiceFor(row: ChatRow?): TtsVoice? {
        val voices = TtsVoices.list(context)
        if (voices.isEmpty()) return null
        val chatVoice = row?.voiceId
        if (!chatVoice.isNullOrBlank()) {
            voices.firstOrNull { it.id == chatVoice }?.let { return it }
        }
        val charVoice = row?.characterId?.let { cid ->
            runCatching { db.listCharacters().firstOrNull { it.id == cid }?.voiceId }.getOrNull()
        }
        return voices.firstOrNull { it.id == charVoice } ?: voices.firstOrNull()
    }

    /**
     * 生成一条回答。providerOverride 非空时临时使用该来源（不落设置）。
     * 返回 (文本, 模型标识)
     */
    suspend fun generateStreaming(
        chat: ChatRow,
        userText: String,
        parentId: Long?,
        providerOverride: String?,
        onDelta: (String) -> Unit,
    ): Pair<String, String> {
        val chatSel = ModelPrefs.chatSel(db)
        val provider = providerOverride ?: if (ModelPrefs.isCloud(chatSel)) "cloud" else "local"
        val cloudRow = ModelPrefs.cloudModel(db, chatSel)
        val cfg = if (provider == "cloud") {
            cloudRow?.let { Llm.Config(it.baseUrl, it.apiKey, it.model) } ?: Llm.loadConfig(db)
        } else null
        if (provider == "cloud" && cfg == null) {
            return "（未配置云端模型：请到 设置 → 模型选择 添加云端 API，或切换为本地模型）" to "none"
        }
        val gp = GenParamsStore.effective(db, chat.id, chat.mode)
        val localCatalog = ModelPrefs.localModel(chatSel)
        val localFile = localCatalog?.let { Catalog.file(context, it) }
        val localLabel = localCatalog?.id ?: "qwen2.5-1.5b"

        val card = chat.characterId?.let { cid ->
            withContext(Dispatchers.IO) {
                db.listCharacters().firstOrNull { it.id == cid }?.let { cr -> CardParser.fromJson(cr.cardJson, null) }
            }
        }
        val budget = PromptPolicy.forMode(chat.mode, provider)
        val assistantBlock = chat.assistantPrompt?.takeIf { it.isNotBlank() }
        val personaCore = card?.let { CardParser.systemPromptFor(it, ProfileStore.displayName(db)) }
            ?: "你是一个中文助手。默认用简体中文回答；回答简洁自然，不要重复用户的问题，也不要输出任何标记或特殊符号。"
        val personaBase = if (assistantBlock != null) "$assistantBlock\n\n$personaCore" else personaCore
        val personaFixed = PromptPolicy.trim(personaBase, budget.persona)
        // 世界书：绑定了集锦就拼进系统提示词（按预算裁剪）
        val loreBlock = if (chat.mode == MODE_CHAT && budget.lore > 0) {
            withContext(Dispatchers.IO) {
                runCatching { lorebookBlock(context, db, chat.id, currentLoreIds, userText) }.getOrNull()
            }
        } else null
        val loreText = loreBlock?.let { PromptPolicy.trim(it, budget.lore) }.orEmpty()
        // 长会话记要（摘要）
        val summaryText = withContext(Dispatchers.IO) { db.latestSummary(chat.id)?.text }
            ?.let { PromptPolicy.trim(it, budget.summary) }
            ?.takeIf { it.isNotBlank() }
            ?.let {
                if (chat.mode == MODE_WORK) "【会话记要（结论/依据/待办）】\n$it"
                else "【前情摘要】\n$it"
            }.orEmpty()
        val memoryText = if (budget.memory > 0) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val hits = LongMemory.recall(context, db, chat.id, userText)
                    LongMemory.format(hits, budget.memory)
                }.getOrDefault("")
            }
        } else ""
        val presetPrompt = gp.systemPrompt?.takeIf { it.isNotBlank() }
            ?.let { PromptPolicy.trim(it, budget.persona) }
            .orEmpty()
        val persona = ProfileStore.augment(
            db,
            listOf(presetPrompt, personaFixed, loreText, summaryText, memoryText).filter { it.isNotBlank() }.joinToString("\n\n")
        )

        withContext(Dispatchers.IO) {
            runCatching {
                db.log(
                    "prompt", chat.id, provider,
                    "persona=${persona.length} lore=${loreText.length} summary=${summaryText.length} memory=${memoryText.length} temp=${gp.temperature} max=${gp.maxTokens}"
                )
            }
        }

        if (chat.mode == MODE_WORK) {
            val kbIds = kbIdsOf(chat)
            if (kbIds.isEmpty()) {
                // 没有引用知识库：直接当普通对话
                val history = PromptPolicy.fitHistory(
                    withContext(Dispatchers.IO) { db.messages(chat.id) }
                        .filter { it.id != parentId && (it.role == "user" || it.role == "assistant") }
                        .map { (if (it.role == "user") "user" else "assistant") to it.content },
                    budget.history
                )
                return if (provider == "local") {
                    localCancel.set(false)
                    withContext(Dispatchers.IO) {
                        LocalLlm.chat(context, persona, history, userText, maxTokens = gp.maxTokens, temperature = gp.temperature.toFloat()) { piece ->
                            onDelta(piece)
                            !localCancel.get()
                        }
                    }.getOrElse { "（本地模型生成失败：${it.message}）" } to "local:qwen2.5-1.5b"
                } else {
                    val c = StreamCanceller()
                    canceller = c
                    withContext(Dispatchers.IO) { Llm.chatStream(cfg!!, persona, history, userText, onDelta, c, params = gp) }
                        .getOrElse { "（生成失败：${it.message}）" } to ("cloud:" + cfg!!.model)
                }
            }

            val emb = withContext(Dispatchers.Default) { EmbedderHolder.get(context).embed(userText) }
            val kbsAll = withContext(Dispatchers.IO) { db.listKbs() }
            // P2-3：所有库候选放在一起做统一归一化，避免小库最高分被拉到 1.0 挤掉相关大库
            val tops = kbIds.associateWith { id -> kbsAll.firstOrNull { it.id == id }?.topK ?: 5 }
            val usedTopKs = tops.values.toList()
            val hits = withContext(Dispatchers.IO) {
                Hybrid.searchMulti(db, kbIds, userText, emb, tops, 8)
            }
            lastTopK = usedTopKs.maxOrNull() ?: 5
            lastHitCount = hits.size
            lastTopScore = hits.maxOfOrNull { it.dense } ?: 0f
            // 按预算装片段：本地每段更短，云端可放更长；只把真正装进去的算作引用
            val perChunk = if (provider == "local") 220 else 420
            val kbBudget = if (budget.kb > 0) budget.kb else if (provider == "local") 1600 else 3800
            val included = ArrayList<Hit>()
            var usedChars = 0
            for (h in hits) {
                val body = PromptPolicy.trim(h.text, perChunk)
                val cost = body.length + h.docName.length + 24
                if (usedChars + cost > kbBudget && included.isNotEmpty()) break
                included.add(h)
                usedChars += cost
                if (usedChars >= kbBudget) break
            }
            val citedHits = included
            lastCitations = org.json.JSONArray().apply {
                citedHits.forEachIndexed { i, h ->
                    put(
                        org.json.JSONObject().apply {
                            put("n", i + 1)
                            put("docId", h.docId)
                            put("doc", h.docName)
                            put("page", h.page)
                            put(
                                "unit",
                                when (runCatching { db.docById(h.docId)?.kind }.getOrNull()) {
                                    "pdf" -> "页"
                                    "epub" -> "章"
                                    "pptx" -> "页"
                                    "csv" -> "行块"
                                    else -> "节"
                                }
                            )
                        }
                    )
                }
            }.toString()

            return if (provider == "local") {
                val ctxText = included.take(2).mapIndexed { i, h ->
                    "[${i + 1}]《${h.docName}》第${h.page}页\n" + PromptPolicy.trim(h.text, 250)
                }.joinToString("\n\n")
                val sys = ProfileStore.augment(
                    db,
                    (if (assistantBlock != null) "$assistantBlock\n" else "") +
                        "只能依据资料回答，资料不足就直接说没有；引用用 [1] 标注。回答简短。"
                )
                val question = "资料片段：\n$ctxText\n\n问题：$userText"
                localCancel.set(false)
                withContext(Dispatchers.IO) {
                    LocalLlm.chat(context, sys, emptyList(), question, maxTokens = gp.maxTokens, temperature = gp.temperature.toFloat(), modelFile = localFile) { piece ->
                        onDelta(piece)
                        !localCancel.get()
                    }
                }.getOrElse { "（本地模型生成失败：${it.message}）" } to "local:$localLabel"
            } else {
                val c = StreamCanceller()
                canceller = c
                val sys = (if (assistantBlock != null) "$assistantBlock\n" else "") +
                    "你是一个严谨的知识库助手。只能依据给定的资料片段回答；资料不足直接说“资料中没有提到”。引用用 [1][2] 标注。"
                withContext(Dispatchers.IO) {
                    Llm.answerStreamCustom(cfg!!, ProfileStore.augment(db, sys), userText, included, onDelta, c, gp)
                }.getOrElse { "（生成失败：${it.message}）" } to ("cloud:" + cfg!!.model)
            }
        }

        val historyAll = withContext(Dispatchers.IO) { db.messages(chat.id) }
            .filter { it.id != parentId }
            .filter { it.role == "user" || it.role == "assistant" }
            .map { m ->
                val parts = runCatching {
                    val arr = org.json.JSONArray(m.attachments ?: "[]")
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.getJSONObject(i)
                        if (o.optString("type") == "file") {
                            "[文档内容：" + o.optString("name") + "]\n" + o.optString("text")
                        } else {
                            val d = o.optString("desc")
                            if (d.isBlank()) null else "[图片描述]\n" + d
                        }
                    }.filter { it.isNotBlank() }
                }.getOrDefault(emptyList())
                val content = if (parts.isEmpty()) m.content else
                    m.content + "\n\n" + parts.joinToString("\n\n")
                (if (m.role == "user") "user" else "assistant") to content
            }
        val history = PromptPolicy.fitHistory(historyAll, budget.history)
        return if (provider == "local") {
            localCancel.set(false)
            withContext(Dispatchers.IO) {
                LocalLlm.chat(context, persona, history, userText, maxTokens = gp.maxTokens, temperature = gp.temperature.toFloat(), modelFile = localFile) { piece ->
                    onDelta(piece)
                    !localCancel.get()
                }
            }.getOrElse { "（本地模型生成失败：${it.message}）" } to "local:$localLabel"
        } else {
            val c = StreamCanceller()
            canceller = c
            withContext(Dispatchers.IO) { Llm.chatStream(cfg!!, persona, history, userText, onDelta, c, params = gp) }
                .getOrElse { "（生成失败：${it.message}）" } to ("cloud:" + cfg!!.model)
        }
    }

    /** 带节流的流式回调：把片段拼起来，最多 60ms 刷一次 UI。 */
    fun deltaSink(): (String) -> Unit {
        val buf = StringBuilder()
        var last = 0L
        return { piece ->
            buf.append(piece)
            val now = android.os.SystemClock.uptimeMillis()
            if (now - last >= 60) {
                last = now
                val snapshot = buf.toString()
                scope.launch(Dispatchers.Main) { streamingText = snapshot }
            }
        }
    }

    fun stopStreaming() {
        canceller?.cancel()
        localCancel.set(true)
    }

    fun send() {
        val text = input.trim()
        val row = current
        val img = pendingImage
        val docPath = pendingDocPath
        val docName = pendingDocName
        val docText = pendingDocText
        if ((text.isEmpty() && img == null && docPath == null) || sending) return
        if (row == null) {
            notice = "请先在侧边栏里新建一个对话"
            return
        }
        input = ""
        pendingImage = null
        pendingDocPath = null
        pendingDocName = null
        pendingDocText = null
        sending = true
        lastCitations = null
        lastHitCount = 0
        lastTopScore = 0f
        streamingText = ""
        streamingModel = null
        scope.launch {
            try {
                val parent = messages.lastOrNull()?.id
                // 图片：先走视觉模型拿描述，再作为本条消息上下文
                var attachmentsJson: String? = null
                var enriched = text
                if (docPath != null) {
                    attachmentsJson = org.json.JSONArray().put(
                        org.json.JSONObject()
                            .put("type", "file")
                            .put("path", docPath)
                            .put("name", docName ?: "文件")
                            .put("chars", docText?.length ?: 0)
                            .put("text", docText ?: "")
                    ).toString()
                    enriched = buildString {
                        append(text)
                        append("\n\n[附件：").append(docName ?: "文件").append("]\n")
                        append(docText ?: "")
                    }.trim()
                }
                if (img != null) {
                    visionBusy = true
                    val desc = withContext(Dispatchers.IO) {
                        val visionSel = ModelPrefs.visionSel(db)
                        val prompt = "请用中文描述这张图片；如果图中有文字，请完整提取出来。"
                        val localText = if (!ModelPrefs.isCloud(visionSel)) {
                            val vm = ModelPrefs.localModel(visionSel)
                            if (vm != null && Catalog.isInstalled(context, vm)) {
                                LocalVision.describeModel(context, vm, img, prompt, 256).getOrNull()
                            } else null
                        } else null
                        if (!localText.isNullOrBlank()) {
                            localText
                        } else {
                            val row = ModelPrefs.cloudModel(db, visionSel)
                            val cfg = row?.let { Vision.Config(it.baseUrl, it.apiKey, it.model) } ?: Vision.loadConfig(db)
                            if (cfg == null) null else runCatching {
                                val bytes = java.io.File(img).readBytes()
                                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                Vision.describe(cfg, b64).getOrNull()
                            }.getOrNull()
                        }
                    }
                    visionBusy = false
                    if (desc != null) pendingImageDesc = desc
                    attachmentsJson = org.json.JSONArray().put(
                        org.json.JSONObject()
                            .put("type", "image")
                            .put("path", img)
                            .put("desc", desc ?: "")
                    ).toString()
                    enriched = buildString {
                        append(text)
                        if (desc != null) append("\n\n[图片内容]\n").append(desc)
                    }.trim()
                }
                val display = when {
                    text.isNotEmpty() -> text
                    docPath != null -> "[文件] " + (docName ?: "")
                    else -> "[图片]"
                }
                val userMsgId = withContext(Dispatchers.IO) {
                    db.addMessage(row.id, "user", display, parent, attachments = attachmentsJson)
                }
                messages = messages + MessageRow(
                    userMsgId, row.id, "user", display, System.currentTimeMillis(), parent,
                    attachments = attachmentsJson
                )
                val (rawReply, modelLabel) = generateStreaming(row, enriched, userMsgId, null, deltaSink())
                val reply = if (rawReply.isBlank()) "（已停止生成）" else rawReply
                streamingModel = modelLabel
                val id = withContext(Dispatchers.IO) {
                    db.addMessage(row.id, "assistant", reply, userMsgId, modelLabel, lastCitations)
                }
                hintFor(lastTopK, lastHitCount, lastTopScore, row.mode)?.let { h ->
                    hintText = h
                    hintMsgId = id
                }
                messages = messages + MessageRow(
                    id, row.id, "assistant", reply, System.currentTimeMillis(), userMsgId, modelLabel,
                    citations = lastCitations
                )
                withContext(Dispatchers.IO) {
                    db.log("send", id, modelLabel, text.take(40))
                    db.touchChat(row.id)
                    if (messages.count { it.role == "user" } == 1) db.touchChat(row.id, text.take(14))
                    // 长记忆：把这轮问答写成可检索片段
                    runCatching {
                        val memUser = if (row.mode == MODE_WORK) "" else text
                        LongMemory.remember(context, db, row.id, id, memUser, reply)
                    }
                    // 长会话：超过窗口就压缩早期对话（失败静默，不影响本轮）
                    runCatching {
                        val chatSel = ModelPrefs.chatSel(db)
                        val prov = if (ModelPrefs.isCloud(chatSel)) "cloud" else "local"
                        val cfg = ModelPrefs.cloudModel(db, chatSel)?.let {
                            Llm.Config(it.baseUrl, it.apiKey, it.model)
                        } ?: Llm.loadConfig(db)
                        val localFile = ModelPrefs.localModel(chatSel)?.let { Catalog.file(context, it) }
                        ChatSummarizer.maybeSummarize(context, db, row, prov, cfg, localFile)
                    }
                }
                reloadLists()
            } catch (t: Throwable) {
                notice = "（出错：${t.message}）"
            } finally {
                sending = false
                streamingText = null
                streamingModel = null
                canceller = null
            }
        }
    }

    fun regenerate(aiMsg: MessageRow, providerOverride: String?, kind: String) {
        val row = current ?: return
        val parentId = aiMsg.parentId
        val userText = messages.firstOrNull { it.id == parentId }?.content
            ?: messages.lastOrNull { it.role == "user" && it.id < aiMsg.id }?.content
            ?: return
        sending = true
        streamingText = ""
        scope.launch {
            try {
                val (rawReply2, modelLabel) = generateStreaming(row, userText, parentId, providerOverride, deltaSink())
                val reply = if (rawReply2.isBlank()) "（已停止生成）" else rawReply2
                val newId = withContext(Dispatchers.IO) {
                    val id = db.addMessage(row.id, "assistant", reply, parentId, modelLabel, lastCitations)
                    db.log(kind, id, modelLabel, userText.take(40))
                    id
                }
                hintFor(lastTopK, lastHitCount, lastTopScore, row.mode)?.let { h ->
                    hintText = h
                    hintMsgId = newId
                }
                reloadMessages()
            } catch (t: Throwable) {
                notice = "（生成失败：${t.message}）"
            } finally {
                sending = false
                streamingText = null
                streamingModel = null
                canceller = null
            }
        }
    }

    fun deleteAttachFiles(rows: List<MessageRow>) {
        rows.forEach { m ->
            parseAttachments(m.attachments).forEach { item ->
                val refs = runCatching { db.attachmentRefCount(item.path, m.id) }.getOrDefault(0)
                if (refs <= 0) runCatching { java.io.File(item.path).delete() }
            }
        }
    }

    fun copyMessage(m: MessageRow) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("message", m.content))
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        }
        scope.launch { withContext(Dispatchers.IO) { db.log("copy", m.id, null) } }
    }

    fun branchFrom(m: MessageRow) {
        val row = current ?: return
        scope.launch {
            val newId = withContext(Dispatchers.IO) { db.branchChat(row, m.id) }
            withContext(Dispatchers.IO) { db.log("branch", m.id, null, "from chat ${row.id}") }
            val newRow = withContext(Dispatchers.IO) { db.chatById(newId) }
            reloadLists()
            if (newRow != null) openChat(newRow)
            drawerState.close()
            Toast.makeText(context, "已创建分支", Toast.LENGTH_SHORT).show()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = ChDrawer, modifier = Modifier.width(300.dp)) {
                Spacer(Modifier.height(28.dp))
                Text(
                    "Ophelia", color = AppColors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(20.dp))
                DrawerAction("新对话", Icons.Filled.Chat) { showNewChatMenu = true }
                DrawerAction("新工作任务", Icons.Filled.Work) { showNewWorkMenu = true }
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = AppColors.divider)
                Spacer(Modifier.height(10.dp))

                LazyColumn(Modifier.weight(1f)) {
                    item { DrawerGroupTitle("过往对话") }
                    if (chatsChat.isEmpty()) item { DrawerEmpty("暂无对话") }
                    items(chatsChat) { row ->
                        DrawerHistory(row.title, current?.id == row.id, row.originChatId != null, {
                            scope.launch { openChat(row); drawerState.close() }
                        }, {
                            renameTarget = row
                            renameText = row.title
                        }) {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    db.deleteChat(row.id)
                                    if (db.getSetting("last_chat_id") == row.id.toString()) {
                                        db.setSetting("last_chat_id", "")
                                    }
                                }
                                if (current?.id == row.id) {
                                    current = null
                                    messages = emptyList()
                                }
                                reloadLists()
                            }
                        }
                    }
                    item {
                        Spacer(Modifier.height(10.dp))
                        DrawerGroupTitle("过往工作任务")
                    }
                    if (chatsWork.isEmpty()) item { DrawerEmpty("暂无工作任务") }
                    items(chatsWork) { row ->
                        DrawerHistory(row.title, current?.id == row.id, row.originChatId != null, {
                            scope.launch { openChat(row); drawerState.close() }
                        }, {
                            renameTarget = row
                            renameText = row.title
                        }) {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    db.deleteChat(row.id)
                                    if (db.getSetting("last_chat_id") == row.id.toString()) {
                                        db.setSetting("last_chat_id", "")
                                    }
                                }
                                if (current?.id == row.id) {
                                    current = null
                                    messages = emptyList()
                                }
                                reloadLists()
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    ) {
        Scaffold(
            containerColor = ChBg,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(current?.title ?: "对话", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text(
                                when {
                                    current?.originChatId != null -> "分支 · 来自会话 #${current?.originChatId}"
                                    current?.mode == MODE_WORK -> "工作任务"
                                    current?.mode == MODE_CHAT ->
                                        run {
                                            val names = currentLoreNames
                                            when {
                                                names.isEmpty() -> "聊天"
                                                names.size <= 2 -> "聊天 · 世界书：" + names.joinToString("、")
                                                else -> "聊天 · 世界书：" + names.size + " 个（" +
                                                    names.take(2).joinToString("、") + "…）"
                                            }
                                        }
                                    else -> "从左侧菜单开始"
                                },
                                fontSize = 11.sp, color = ChMuted
                            )
                        }
                    },
                    navigationIcon = { SlashMenuIcon { scope.launch { drawerState.open() } } },
                    actions = {
                        IconButton(onClick = {
                            discardIfEmpty(current)
                            onBack()
                        }) { Icon(Icons.Filled.LibraryBooks, "知识库", tint = ChAccent) }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, "会话设置", tint = AppColors.text)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (current?.mode == MODE_WORK) {
                                    DropdownMenuItem(
                                        text = { Text("工作助理") },
                                        onClick = {
                                            menuOpen = false
                                            newAssistantName = current?.assistantName.orEmpty()
                                            newAssistantNote = current?.assistantNote.orEmpty()
                                            newAssistantPrompt = current?.assistantPrompt.orEmpty()
                                            assistantAvatarPath = current?.assistantAvatar
                                            showAssistantEditor = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("引用知识库") },
                                        onClick = { menuOpen = false; showChatKbPicker = true }
                                    )
                                }
                                if (current?.mode == MODE_CHAT) {
                                    DropdownMenuItem(
                                        text = { Text("世界书") },
                                        onClick = { menuOpen = false; showLorePicker = true }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("聊天背景") },
                                    onClick = { menuOpen = false; showBgDialog = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("音色") },
                                    onClick = { menuOpen = false; showVoicePicker = true }
                                )
                                DropdownMenuItem(
                                    text = { Text("会话记忆") },
                                    onClick = { menuOpen = false; showMemoryDialog = true }
                                )
                            }
                        }
                        IconButton(onClick = {
                            genParams = GenParamsStore.effective(db, current?.id ?: 0L, current?.mode ?: MODE_CHAT)
                            showGenPanel = true
                        }) { Icon(Icons.Filled.Tune, "生成参数", tint = AppColors.text) }
                        IconButton(onClick = { onOpenSettings("root") }) { Icon(Icons.Filled.Settings, "设置", tint = AppColors.text) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = ChBg, titleContentColor = AppColors.text)
                )
            },
            bottomBar = {
                Surface(color = ChBg, modifier = Modifier.imePadding()) {
                    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp)) {
                        val pi = pendingImage
                        if (pi != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val bmp = remember(pi) {
                                    runCatching { android.graphics.BitmapFactory.decodeFile(pi) }.getOrNull()
                                }
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "待发送图片",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (visionBusy) "识别中…" else "图片已就绪",
                                    color = ChMuted, fontSize = 11.sp
                                )
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { pendingImage = null }) {
                                    Icon(Icons.Filled.Close, "移除", tint = ChMuted, modifier = Modifier.size(18.dp))
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        val dp = pendingDocPath
                        if (dp != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Description, "文档", tint = ChAccent, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        pendingDocName ?: "文档",
                                        color = AppColors.text, fontSize = 12.sp, maxLines = 1
                                    )
                                    Text(
                                        if (docBusy) "读取中…" else "已读取 ${pendingDocChars} 字" +
                                            (if (pendingDocFull > pendingDocChars) "（仅取前 8000 字）" else ""),
                                        color = ChMuted, fontSize = 11.sp
                                    )
                                }
                                IconButton(onClick = {
                                    pendingDocPath = null
                                    pendingDocName = null
                                    pendingDocText = null
                                }) {
                                    Icon(Icons.Filled.Close, "移除", tint = ChMuted, modifier = Modifier.size(18.dp))
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                IconButton(onClick = { attachMenu = true }) {
                                    Icon(Icons.Filled.AttachFile, "添加附件", tint = ChMuted)
                                }
                                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("图片（视觉识别）") },
                                        onClick = {
                                            attachMenu = false
                                            imagePicker.launch("image/*")
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("文档（PDF/Word/TXT…）") },
                                        onClick = {
                                            attachMenu = false
                                            docPicker.launch(arrayOf("*/*"))
                                        }
                                    )
                                }
                            }
                            OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = {
                                Text(
                                    if (current?.mode == MODE_WORK) "问点什么，会检索知识库…" else "说点什么…",
                                    color = ChMuted
                                )
                            },
                            modifier = Modifier.weight(1f),
                            maxLines = 4
                        )
                        Spacer(Modifier.width(8.dp))
                        if (sending) {
                            IconButton(onClick = { stopStreaming() }) {
                                Icon(Icons.Filled.Stop, "停止生成", tint = AppColors.danger)
                            }
                        } else {
                            IconButton(onClick = { send() }, enabled = input.isNotBlank() || pendingImage != null || pendingDocPath != null) {
                                Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = ChAccent)
                            }
                        }
                        }
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                val bgPath = chatBg
                if (bgPath != null) {
                    val bmp = remember(bgPath) {
                        runCatching {
                            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            android.graphics.BitmapFactory.decodeFile(bgPath, bounds)
                            var sample = 1
                            val target = 1440
                            while (bounds.outWidth / sample > target) sample *= 2
                            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                            android.graphics.BitmapFactory.decodeFile(bgPath, opts)
                        }.getOrNull()
                    }
                    if (bmp != null) {
                        val blurMod = if (bgBlur > 0.5f && android.os.Build.VERSION.SDK_INT >= 31) {
                            Modifier.blur(bgBlur.dp)
                        } else Modifier
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().then(blurMod)
                        )
                        Box(
                            Modifier.fillMaxSize().background(AppColors.bg.copy(alpha = bgDim / 100f))
                        )
                    }
                }
                if (current == null) {
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("点左上角「//」打开菜单", color = ChMuted, fontSize = 14.sp)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { showNewChatMenu = true }) { Text("新对话") }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { showNewWorkMenu = true }) { Text("新工作任务") }
                    }
                    return@Box
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    items(messages) { m ->
                        val mine = m.role == "user"
                        val open = expanded[m.id] == true
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                            verticalAlignment = Alignment.Top
                        ) {
                            if (!mine) {
                                AvatarCircle(aiAvatar, 36.dp, fallbackText = "AI")
                                Spacer(Modifier.width(8.dp))
                                Column(horizontalAlignment = Alignment.Start) {
                                    val longText = m.content.length > 600
                                    val showFull = textExpanded[m.id] == true
                                    Surface(
                                        color = ChCard,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.clickable { expanded[m.id] = !open }
                                    ) {
                                        Column(Modifier.padding(12.dp)) {
                                            AttachmentViews(m.attachments)
                                            val shown = if (longText && !showFull) m.content.take(600) + "…" else m.content
                                            Text(shown, color = AppColors.text, fontSize = 14.sp)
                                            if (longText && !showFull) {
                                                Text(
                                                    "展开全文",
                                                    color = ChAccent, fontSize = 11.sp,
                                                    modifier = Modifier.clickable { textExpanded[m.id] = true }
                                                )
                                            }
                                            if (m.edited) {
                                                Spacer(Modifier.height(4.dp))
                                                Text("已手动修改", color = ChAccent, fontSize = 10.sp)
                                            }
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                                    .format(java.util.Date(m.createdAt)),
                                                color = AppColors.muted, fontSize = 10.sp
                                            )
                                            val ht = hintText
                                            if (ht != null && hintMsgId == m.id) {
                                                Spacer(Modifier.height(3.dp))
                                                Text(ht, color = AppColors.muted, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                    val cites = remember(m.id, m.citations) {
                                        runCatching {
                                            val arr = org.json.JSONArray(m.citations ?: "[]")
                                            (0 until arr.length()).map { i ->
                                                val o = arr.getJSONObject(i)
                                                Triple(o.optInt("docId"), o.optString("doc"), o.optInt("page"))
                                                    .let { it to o.optString("unit", "页") }
                                            }
                                        }.getOrDefault(emptyList())
                                    }
                                    if (cites.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            cites.take(5).forEachIndexed { idx, pair ->
                                                val c = pair.first
                                                val unit = pair.second
                                                AssistChip(
                                                    onClick = {
                                                        if (c.first > 0) onOpenCitation(c.first.toLong(), c.third)
                                                    },
                                                    label = {
                                                        Text(
                                                            "[${idx + 1}] 第${c.third}$unit",
                                                            fontSize = 10.sp, color = ChAccent
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    if (open) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            ActionIcon(Icons.Filled.VolumeUp, "朗读") {
                                                if (tts.speaking) {
                                                    tts.stop()
                                                } else {
                                                    val readActions = db.getSetting("tts_read_actions") == "1"
                                                    val speakText = if (current?.mode == MODE_WORK) stripAction(m.content) else ttsText(m.content, readActions)
                                                    if (speakText.isBlank()) {
                                                        android.widget.Toast.makeText(
                                                            context, "这条消息只有动作描写", android.widget.Toast.LENGTH_SHORT
                                                        ).show()
                                                    } else {
                                                        android.util.Log.i("TtsStrip", "msg=${m.id} in=${m.content.length} out=${speakText.length}")
                                                        tts.speak(speakText, voiceFor(current))
                                                    }
                                                }
                                            }
                                            ActionIcon(Icons.Filled.ContentCopy, "复制") { copyMessage(m) }
                                            ActionIcon(Icons.Filled.Refresh, "重新生成") {
                                                if (!sending) regenerate(m, null, "regenerate")
                                            }
                                            ActionIcon(Icons.Filled.SwapHoriz, "切换模型") {
                                                if (!sending) modelPickerFor = m
                                            }
                                            ActionIcon(Icons.Filled.Delete, "删除") { deleteTarget = m }
                                            ActionIcon(Icons.Filled.CallSplit, "分支") {
                                                if (!sending) branchFrom(m)
                                            }
                                            ActionIcon(Icons.Filled.Edit, "修改") {
                                                if (!sending) editTarget = m
                                            }
                                        }
                                    }
                                }
                            } else {
                                Column(horizontalAlignment = Alignment.End) {
                                    Surface(
                                        color = ChUser,
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.clickable { expanded[m.id] = !open }
                                    ) {
                                        Column(Modifier.padding(12.dp)) {
                                            AttachmentViews(m.attachments)
                                            if (!m.content.startsWith("[图片]") && !m.content.startsWith("[文件]")) {
                                                Text(m.content, color = AppColors.text, fontSize = 14.sp)
                                            }
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                                    .format(java.util.Date(m.createdAt)),
                                                color = AppColors.muted, fontSize = 10.sp
                                            )
                                        }
                                    }
                                    if (open) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            ActionIcon(Icons.Filled.ContentCopy, "复制") { copyMessage(m) }
                                            ActionIcon(Icons.Filled.Delete, "删除") { deleteTarget = m }
                                            ActionIcon(Icons.Filled.Edit, "修改") { editTarget = m }
                                        }
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                AvatarCircle(meAvatar, 36.dp, fallbackIcon = Icons.Filled.Person)
                            }
                        }
                    }
                    streamingText?.let { st ->
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                                Surface(color = ChCard, shape = RoundedCornerShape(14.dp)) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(
                                            if (st.isEmpty()) "正在生成…" else st,
                                            color = AppColors.text, fontSize = 14.sp
                                        )
                                        streamingModel?.let {
                                            Spacer(Modifier.height(4.dp))
                                            Text(it, color = ChMuted, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (notice != null) item { Text(notice!!, color = AppColors.warn, fontSize = 12.sp) }
                }

                if (amb.playing) {
                    Surface(
                        color = AppColors.card.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                    ) {
                        Text(
                            "情境声：${amb.current?.name ?: ""}", color = ChAccent, fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }
    }

    // ---------- 会话记忆（摘要 + 长记忆） ----------
    if (showMemoryDialog && current != null) {
        val cr = current!!
        LaunchedEffect(showMemoryDialog) {
            withContext(Dispatchers.IO) {
                memSummary = db.latestSummary(cr.id)
                memCount = runCatching { db.listTurnMemories(cr.id, 1000).size }.getOrDefault(0)
            }
        }
        val chatSel = ModelPrefs.chatSel(db)
        val provider = if (ModelPrefs.isCloud(chatSel)) "cloud" else "local"
        AlertDialog(
            onDismissRequest = { showMemoryDialog = false },
            containerColor = AppColors.card,
            titleContentColor = AppColors.text,
            textContentColor = AppColors.text,
            title = { Text("会话记忆") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("摘要", color = AppColors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    val sum = memSummary
                    Text(
                        if (sum == null) "还没有摘要（对话超过 20 条会自动生成）"
                        else "已覆盖前 ${sum.coveredTo} 条 · ${sum.text.length} 字",
                        color = AppColors.muted, fontSize = 12.sp
                    )
                    sum?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it.text, color = AppColors.muted, fontSize = 11.sp, maxLines = 8)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row {
                        TextButton(enabled = !memBusy, onClick = {
                            scope.launch {
                                memBusy = true
                                withContext(Dispatchers.IO) {
                                    runCatching {
                                        val cfg = ModelPrefs.cloudModel(db, chatSel)?.let {
                                            Llm.Config(it.baseUrl, it.apiKey, it.model)
                                        } ?: Llm.loadConfig(db)
                                        val localFile = ModelPrefs.localModel(chatSel)?.let { Catalog.file(context, it) }
                                        ChatSummarizer.summarizeNow(context, db, cr, provider, cfg, localFile)
                                    }
                                    memSummary = db.latestSummary(cr.id)
                                }
                                memBusy = false
                                notice = if (memSummary == null) "摘要生成失败" else "摘要已更新"
                            }
                        }) { Text(if (memBusy) "生成中…" else "重新摘要", color = AppColors.accent, fontSize = 13.sp) }
                        TextButton(enabled = memSummary != null, onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { db.deleteSummary(cr.id) }
                                memSummary = null
                            }
                        }) { Text("清空摘要", color = AppColors.muted, fontSize = 13.sp) }
                    }
                    Divider(color = AppColors.divider)
                    Spacer(Modifier.height(8.dp))
                    Text("长记忆", color = AppColors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("$memCount 条（最近 6 条不参与召回）", color = AppColors.muted, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    TextButton(enabled = memCount > 0, onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { db.deleteTurnMemories(cr.id) }
                            memCount = 0
                        }
                    }) { Text("清空记忆", color = AppColors.danger, fontSize = 13.sp) }
                }
            },
            confirmButton = { TextButton(onClick = { showMemoryDialog = false }) { Text("关闭") } }
        )
    }

    // ---------- 生成参数（ST 面板） ----------
    if (showGenPanel && current != null) {
        val chatRow = current!!
        val presetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use {
                            it.bufferedReader(Charsets.UTF_8).readText()
                        }
                    }.getOrNull()
                }
                val parsed = text?.let { GenParams.fromStPreset(it) }
                if (parsed == null) {
                    notice = "预设解析失败：不是有效的 ST 预设 JSON"
                } else {
                    genParams = parsed
                    withContext(Dispatchers.IO) { GenParamsStore.saveChat(db, chatRow.id, parsed) }
                    notice = "已导入预设：温度 ${parsed.temperature}，长度 ${parsed.maxTokens}" +
                        if (parsed.systemPrompt != null) "，含系统提示词" else ""
                }
            }
        }
        fun persist(p: GenParams) {
            genParams = p
            scope.launch { withContext(Dispatchers.IO) { GenParamsStore.saveChat(db, chatRow.id, p) } }
        }
        ModalBottomSheet(
            onDismissRequest = { showGenPanel = false },
            containerColor = AppColors.card,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 28.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("生成设置", color = AppColors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    Text("跟随全局", color = AppColors.muted, fontSize = 12.sp)
                    Spacer(Modifier.width(6.dp))
                    Switch(
                        checked = genParams.followGlobal,
                        onCheckedChange = { persist(genParams.copy(followGlobal = it)) }
                    )
                }
                Text(
                    if (genParams.followGlobal) "当前跟随全局设置；关闭后只对本次会话生效。"
                    else "只对本次会话生效。",
                    color = AppColors.muted, fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))

                @Composable
                fun slider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, show: String, onChange: (Float) -> Unit) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = AppColors.text, fontSize = 13.sp, modifier = Modifier.width(74.dp))
                        Slider(
                            value = value, onValueChange = onChange,
                            valueRange = range,
                            enabled = !genParams.followGlobal,
                            modifier = Modifier.weight(1f)
                        )
                        Text(show, color = AppColors.muted, fontSize = 12.sp, modifier = Modifier.width(52.dp))
                    }
                }

                slider("温度", genParams.temperature.toFloat(), 0f..1.5f,
                    String.format("%.2f", genParams.temperature)) {
                    var v = it.toDouble()
                    if (chatRow.mode == MODE_WORK && v > 0.6) v = 0.6
                    persist(genParams.copy(temperature = v))
                }
                if (chatRow.mode == MODE_WORK) {
                    Text("工作任务的温度上限为 0.60", color = AppColors.muted, fontSize = 10.sp)
                }
                slider("top_p", genParams.topP.toFloat(), 0.1f..1f,
                    String.format("%.2f", genParams.topP)) { persist(genParams.copy(topP = it.toDouble())) }
                slider("top_k", genParams.topK.toFloat(), 0f..100f, "${genParams.topK}") {
                    persist(genParams.copy(topK = it.toInt()))
                }
                Text("top_k 仅本地模型生效", color = AppColors.muted, fontSize = 10.sp)
                slider("最大输出", genParams.maxTokens.toFloat(), 64f..2048f, "${genParams.maxTokens}") {
                    persist(genParams.copy(maxTokens = (it / 16).toInt() * 16))
                }
                slider("重复惩罚", genParams.repPenalty.toFloat(), 1f..1.5f,
                    String.format("%.2f", genParams.repPenalty)) {
                    persist(genParams.copy(repPenalty = it.toDouble()))
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = genParams.stop.joinToString("\n"),
                    onValueChange = { text ->
                        val list = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                        persist(genParams.copy(stop = list))
                    },
                    label = { Text("停止词（每行一个）") },
                    enabled = !genParams.followGlobal,
                    minLines = 2, maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = genParams.systemPrompt.orEmpty(),
                    onValueChange = { text ->
                        persist(genParams.copy(systemPrompt = text.takeIf { it.isNotBlank() }))
                    },
                    label = { Text("预设系统提示词（导入 ST 预设时自动填入）") },
                    enabled = !genParams.followGlobal,
                    minLines = 3, maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { presetPicker.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                        Text("导入 ST 预设", color = AppColors.accent, fontSize = 13.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        val d = GenParams()
                        genParams = d
                        scope.launch { withContext(Dispatchers.IO) { GenParamsStore.clearChat(db, chatRow.id) } }
                    }) { Text("恢复默认", color = AppColors.muted, fontSize = 13.sp) }
                }
            }
        }
    }

    // ---------- 首次启动引导 ----------
    if (showOnboarding) {
        fun finishGuide(target: String?) {
            db.setSetting("onboarding_done", "1")
            if (target != null) db.setSetting("guide_target", target)
            showOnboarding = false
            if (target != null) onOpenSettings("model")
        }
        AlertDialog(
            onDismissRequest = { /* 必须选一项或点“直接进入” */ },
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            ),
            containerColor = AppColors.card,
            titleContentColor = AppColors.text,
            textContentColor = AppColors.text,
            title = { Text("开始使用 Ophelia", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text("选一种方式开始，之后可在「设置 → 模型选择」随时切换。", color = AppColors.muted, fontSize = 12.sp)
                    Spacer(Modifier.height(14.dp))
                    ListItem(
                        headlineContent = { Text("配置云端 API") },
                        supportingContent = {
                            Text("填写地址与密钥，立刻可用；不需要下载大文件", color = AppColors.muted, fontSize = 11.sp)
                        },
                        leadingContent = { Icon(Icons.Filled.Cloud, null, tint = AppColors.accent) },
                        modifier = Modifier.clickable { finishGuide("cloud") }
                    )
                    Spacer(Modifier.height(6.dp))
                    ListItem(
                        headlineContent = { Text("下载本地模型") },
                        supportingContent = {
                            Text("完全离线，需下载 0.4–1.7 GB；手机配置较好时更推荐", color = AppColors.muted, fontSize = 11.sp)
                        },
                        leadingContent = { Icon(Icons.Filled.Download, null, tint = AppColors.accent) },
                        modifier = Modifier.clickable { finishGuide("local") }
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "直接进入",
                        color = AppColors.muted,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { finishGuide(null) }
                            .padding(vertical = 6.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
    }

    // ---------- 新对话：选角色卡 ----------


    if (showCharPicker) {
        AlertDialog(
            onDismissRequest = { showCharPicker = false },
            title = { Text("选择角色卡") },
            text = {
                if (characters.isEmpty()) {
                    Text("还没有角色卡，请先到 设置 → 角色卡 导入", color = ChMuted, fontSize = 13.sp)
                } else {
                    Column {
                        characters.forEach { cr ->
                            ListItem(
                                headlineContent = { Text(cr.name) },
                                supportingContent = {
                                    Text(
                                        if (cr.voiceId.isNullOrBlank()) "未绑定音色" else "已绑定音色",
                                        color = ChMuted, fontSize = 11.sp
                                    )
                                },
                                modifier = Modifier.clickable {
                                    showCharPicker = false
                                    // 卡片自带世界书时自动预选
                                    if (newChatLorebookId == null) {
                                        db.getSetting("char_lore_${cr.id}")?.toLongOrNull()?.let { bid ->
                                            runCatching { db.lorebookById(bid) }.getOrNull()?.let { bk ->
                                                newChatLorebookId = bk.id
                                                newChatLoreName = bk.name
                                            }
                                        }
                                    }
                                    createChatSession(cr.id, newChatTitle.trim())
                                    newChatTitle = ""
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showCharPicker = false }) { Text("取消") } }
        )
    }

    // ---------- 新工作任务 ----------
    if (showNewWorkMenu) {
        AlertDialog(
            onDismissRequest = { showNewWorkMenu = false },
            title = { Text("新工作任务") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newWorkTitle,
                        onValueChange = { newWorkTitle = it },
                        label = { Text("任务名称（可留空）") },
                        placeholder = { Text("留空则按时间命名", color = ChMuted, fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    ListItem(
                        headlineContent = { Text("引用知识库") },
                        supportingContent = {
                            Text(
                                if (newWorkKbs.isEmpty()) "未选择" else "已选 ${newWorkKbs.size} 个知识库",
                                color = if (newWorkKbs.isEmpty()) ChMuted else ChAccent, fontSize = 12.sp
                            )
                        },
                        modifier = Modifier.clickable { showNewKbPicker = true }
                    )
                    ListItem(
                        headlineContent = { Text("设定工作助理") },
                        supportingContent = {
                            Text(
                                newAssistantName.ifBlank { if (newAssistantPrompt.isNotBlank()) "已设定提示词" else "未设置（可选：规定输出格式/模板）" },
                                color = if (newAssistantName.isBlank()) ChMuted else ChAccent, fontSize = 12.sp
                            )
                        },
                        modifier = Modifier.clickable {
                            assistantForNew = true
                            assistantAvatarPath = null
                            // 默认携带写作助理提示词（可改可清空）
                            if (newAssistantPrompt.isBlank()) newAssistantPrompt = DEFAULT_ASSISTANT_PROMPT
                            showAssistantEditor = true
                        }
                    )
                    ListItem(
                        headlineContent = { Text("直接对话") },
                        supportingContent = { Text("不引库、不设助理，直接开始", color = ChMuted, fontSize = 12.sp) },
                        modifier = Modifier.clickable {
                            showNewWorkMenu = false
                            createWorkSession(emptyList(), "", "", "", null, newWorkTitle.trim())
                            newWorkTitle = ""
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showNewWorkMenu = false
                    createWorkSession(
                        newWorkKbs, newAssistantName, newAssistantNote, newAssistantPrompt,
                        assistantAvatarPath, newWorkTitle.trim()
                    )
                    newWorkTitle = ""
                }) { Text("开始") }
            },
            dismissButton = { TextButton(onClick = { showNewWorkMenu = false }) { Text("取消") } }
        )
    }

    // ---------- 知识库多选 ----------
    if (showNewKbPicker || showChatKbPicker) {
        AlertDialog(
            onDismissRequest = { showNewKbPicker = false; showChatKbPicker = false },
            title = { Text("引用知识库") },
            text = {
                if (allKbs.isEmpty()) {
                    Text("还没有知识库", color = ChMuted, fontSize = 13.sp)
                } else {
                    Column {
                        allKbs.forEach { kb ->
                            val checked = pickKbs.contains(kb.id)
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    pickKbs = if (checked) pickKbs - kb.id else pickKbs + kb.id
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = {
                                    pickKbs = if (checked) pickKbs - kb.id else pickKbs + kb.id
                                })
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    Text(kb.name, color = AppColors.text, fontSize = 14.sp)
                                    Text("TopK ${kb.topK}", color = ChMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (showChatKbPicker) {
                        val row = current
                        if (row != null) {
                            val json = org.json.JSONArray(pickKbs.toList()).toString()
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    db.updateChatKbs(row.id, if (pickKbs.isEmpty()) null else json)
                                }
                                val fresh = withContext(Dispatchers.IO) { db.chatById(row.id) }
                                if (fresh != null) current = fresh
                            }
                        }
                        showChatKbPicker = false
                    } else {
                        newWorkKbs = pickKbs.toList()
                        showNewKbPicker = false
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showNewKbPicker = false; showChatKbPicker = false }) { Text("取消") }
            }
        )
    }

    // ---------- 工作助理编辑 ----------
    if (showAssistantEditor) {
        AlertDialog(
            onDismissRequest = { showAssistantEditor = false },
            title = { Text(if (assistantForNew) "设定工作助理" else "工作助理") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarCircle(assistantAvatarPath, 48.dp, fallbackIcon = Icons.Filled.Person)
                        Spacer(Modifier.width(10.dp))
                        TextButton(onClick = { assistantAvatarPicker.launch("image/*") }) {
                            Text("选择头像", color = ChAccent, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = newAssistantName,
                        onValueChange = { newAssistantName = it },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newAssistantNote,
                        onValueChange = { newAssistantNote = it },
                        label = { Text("备注") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newAssistantPrompt,
                        onValueChange = { newAssistantPrompt = it },
                        label = { Text("提示词（规定输出格式/模板）") },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val row = current
                    if (!assistantForNew && row != null) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                db.updateChatAssistant(
                                    row.id,
                                    newAssistantName.ifBlank { null },
                                    newAssistantNote.ifBlank { null },
                                    newAssistantPrompt.ifBlank { null },
                                    assistantAvatarPath
                                )
                            }
                            val fresh = withContext(Dispatchers.IO) { db.chatById(row.id) }
                            if (fresh != null) current = fresh
                        }
                    }
                    showAssistantEditor = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showAssistantEditor = false }) { Text("取消") } }
        )
    }

    // ---------- 音色 ----------
    if (showVoicePicker) {
        val voices = remember(showVoicePicker) { TtsVoices.list(context) }
        val currentVoiceId = current?.let { voiceFor(it)?.id }
        AlertDialog(
            onDismissRequest = { showVoicePicker = false },
            title = { Text("朗读音色") },
            text = {
                if (voices.isEmpty()) {
                    Text("还没有导入声音包", color = ChMuted, fontSize = 13.sp)
                } else {
                    Column {
                        voices.forEach { v ->
                            ListItem(
                                headlineContent = { Text(v.name) },
                                supportingContent = {
                                    if (v.id == currentVoiceId) Text("当前使用", color = ChAccent, fontSize = 11.sp)
                                },
                                modifier = Modifier.clickable {
                                    val row = current
                                    if (row != null) {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { db.updateChatVoice(row.id, v.id) }
                                            val fresh = withContext(Dispatchers.IO) { db.chatById(row.id) }
                                            if (fresh != null) current = fresh
                                        }
                                    }
                                    showVoicePicker = false
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showVoicePicker = false }) { Text("关闭") } }
        )
    }

    // 新建对话时的世界书选择（可多选）
    if (showNewChatLorePicker) {
        var books by remember { mutableStateOf(listOf<Lorebook>()) }
        var picked by remember { mutableStateOf(newChatLoreIds.toSet()) }
        LaunchedEffect(Unit) { books = withContext(Dispatchers.IO) { db.listLorebooks() } }
        AlertDialog(
            onDismissRequest = { showNewChatLorePicker = false },
            containerColor = AppColors.card,
            titleContentColor = AppColors.text,
            textContentColor = AppColors.text,
            title = { Text("选择世界书（可多选）") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (books.isEmpty()) {
                        Text("还没有世界书，去「角色卡/世界书」页新建。", color = AppColors.muted, fontSize = 12.sp)
                    }
                    books.forEach { b ->
                        val checked = b.id in picked
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                picked = if (checked) picked - b.id else picked + b.id
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { v ->
                                picked = if (v) picked + b.id else picked - b.id
                            })
                            Text(b.name, color = AppColors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ids = picked.toList()
                    val names = ids.map { id ->
                        books.firstOrNull { it.id == id }?.name
                            ?: runCatching { db.lorebookById(id)?.name }.getOrNull()
                            ?: "#$id"
                    }
                    newChatLoreIds = ids
                    newChatLoreNames = names
                    newChatLorebookId = ids.firstOrNull()
                    newChatLoreName = names.firstOrNull()
                    showNewChatLorePicker = false
                }) { Text("确定（${picked.size}）", color = AppColors.accent) }
            },
            dismissButton = { TextButton(onClick = { showNewChatLorePicker = false }) { Text("取消") } }
        )
    }

    // 世界书选择（可多选）
    if (showLorePicker) {
        var books by remember { mutableStateOf(listOf<Lorebook>()) }
        var picked by remember { mutableStateOf(currentLoreIds.toSet()) }
        LaunchedEffect(Unit) { books = withContext(Dispatchers.IO) { db.listLorebooks() } }
        AlertDialog(
            onDismissRequest = { showLorePicker = false },
            containerColor = AppColors.card,
            titleContentColor = AppColors.text,
            textContentColor = AppColors.text,
            title = { Text("世界书（可多选）") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "绑定的集锦会拼进系统提示词，只对当前会话生效；多本合计上限 3000 字。",
                        color = AppColors.muted, fontSize = 11.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    if (books.isEmpty()) {
                        Text("还没有世界书，去「角色卡/世界书」页新建。", color = AppColors.muted, fontSize = 12.sp)
                    }
                    books.forEach { b ->
                        val checked = b.id in picked
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                picked = if (checked) picked - b.id else picked + b.id
                            }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { v ->
                                picked = if (v) picked + b.id else picked - b.id
                            })
                            Column(Modifier.weight(1f)) {
                                Text(b.name, color = AppColors.text, fontSize = 14.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = books.filter { it.id in picked }
                    val row = current
                    scope.launch {
                        if (row != null) {
                            withContext(Dispatchers.IO) { db.setChatLorebooks(row.id, chosen.map { it.id }) }
                            current = row.copy(lorebookId = chosen.firstOrNull()?.id)
                        }
                        currentLoreIds = chosen.map { it.id }
                        currentLoreNames = chosen.map { it.name }
                        currentLoreName = currentLoreNames.firstOrNull()
                        showLorePicker = false
                    }
                }) { Text("确定", color = AppColors.accent) }
            },
            dismissButton = { TextButton(onClick = { showLorePicker = false }) { Text("取消") } }
        )
    }

    // ---------- 首次启动引导 ----------
    if (showOnboarding) {
        fun finishGuide(target: String?) {
            db.setSetting("onboarding_done", "1")
            if (target != null) db.setSetting("guide_target", target)
            showOnboarding = false
            if (target != null) onOpenSettings("model")
        }
        AlertDialog(
            onDismissRequest = { /* 必须选一项或点“直接进入” */ },
            properties = androidx.compose.ui.window.DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            ),
            containerColor = AppColors.card,
            titleContentColor = AppColors.text,
            textContentColor = AppColors.text,
            title = { Text("开始使用 Ophelia", fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    Text("选一种方式开始，之后可在「设置 → 模型选择」随时切换。", color = AppColors.muted, fontSize = 12.sp)
                    Spacer(Modifier.height(14.dp))
                    ListItem(
                        headlineContent = { Text("配置云端 API") },
                        supportingContent = {
                            Text("填写地址与密钥，立刻可用；不需要下载大文件", color = AppColors.muted, fontSize = 11.sp)
                        },
                        leadingContent = { Icon(Icons.Filled.Cloud, null, tint = AppColors.accent) },
                        modifier = Modifier.clickable { finishGuide("cloud") }
                    )
                    Spacer(Modifier.height(6.dp))
                    ListItem(
                        headlineContent = { Text("下载本地模型") },
                        supportingContent = {
                            Text("完全离线，需下载 0.4–1.7 GB；手机配置较好时更推荐", color = AppColors.muted, fontSize = 11.sp)
                        },
                        leadingContent = { Icon(Icons.Filled.Download, null, tint = AppColors.accent) },
                        modifier = Modifier.clickable { finishGuide("local") }
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "直接进入",
                        color = AppColors.muted,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { finishGuide(null) }
                            .padding(vertical = 6.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            },
            confirmButton = {},
            dismissButton = {}
        )
    }

    // ---------- 新对话：选角色卡 ----------
    if (showNewChatMenu) {
        AlertDialog(
            onDismissRequest = { showNewChatMenu = false },
            title = { Text("新对话") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newChatTitle,
                        onValueChange = { newChatTitle = it },
                        label = { Text("会话名称（可留空）") },
                        placeholder = { Text("留空则按时间命名", color = ChMuted, fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    ListItem(
                        headlineContent = { Text("世界书（可选）") },
                        supportingContent = {
                            val shown = when {
                                newChatLoreNames.isEmpty() -> "未选择"
                                newChatLoreNames.size <= 2 -> newChatLoreNames.joinToString("、")
                                else -> newChatLoreNames.take(2).joinToString("、") + " 等 ${newChatLoreNames.size} 个"
                            }
                            Text(
                                shown,
                                color = if (newChatLoreNames.isEmpty()) ChMuted else ChAccent,
                                fontSize = 12.sp
                            )
                        },
                        modifier = Modifier.clickable { showNewChatLorePicker = true }
                    )
                    ListItem(
                        headlineContent = { Text("无角色卡") },
                        supportingContent = { Text("使用通用助手", color = ChMuted, fontSize = 12.sp) },
                        modifier = Modifier.clickable {
                            showNewChatMenu = false
                            createChatSession(null, newChatTitle.trim())
                            newChatTitle = ""
                        }
                    )
                    ListItem(
                        headlineContent = { Text("选择一个角色卡") },
                        supportingContent = { Text("绑定后本会话始终使用该角色", color = ChMuted, fontSize = 12.sp) },
                        modifier = Modifier.clickable {
                            showNewChatMenu = false
                            showCharPicker = true
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showNewChatMenu = false }) { Text("取消") } }
        )
    }

    if (showCharPicker) {
        AlertDialog(
            onDismissRequest = { showCharPicker = false },
            title = { Text("选择角色卡") },
            text = {
                if (characters.isEmpty()) {
                    Text("还没有角色卡，请先到 设置 → 角色卡 导入", color = ChMuted, fontSize = 13.sp)
                } else {
                    Column {
                        characters.forEach { cr ->
                            ListItem(
                                headlineContent = { Text(cr.name) },
                                supportingContent = {
                                    Text(
                                        if (cr.voiceId.isNullOrBlank()) "未绑定音色" else "已绑定音色",
                                        color = ChMuted, fontSize = 11.sp
                                    )
                                },
                                modifier = Modifier.clickable {
                                    showCharPicker = false
                                    // 卡片自带世界书时自动预选
                                    if (newChatLorebookId == null) {
                                        db.getSetting("char_lore_${cr.id}")?.toLongOrNull()?.let { bid ->
                                            runCatching { db.lorebookById(bid) }.getOrNull()?.let { bk ->
                                                newChatLorebookId = bk.id
                                                newChatLoreName = bk.name
                                            }
                                        }
                                    }
                                    createChatSession(cr.id, newChatTitle.trim())
                                    newChatTitle = ""
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showCharPicker = false }) { Text("取消") } }
        )
    }

    // ---------- 新工作任务 ----------
    if (showNewWorkMenu) {
        AlertDialog(
            onDismissRequest = { showNewWorkMenu = false },
            title = { Text("新工作任务") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newWorkTitle,
                        onValueChange = { newWorkTitle = it },
                        label = { Text("任务名称（可留空）") },
                        placeholder = { Text("留空则按时间命名", color = ChMuted, fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    ListItem(
                        headlineContent = { Text("引用知识库") },
                        supportingContent = {
                            Text(
                                if (newWorkKbs.isEmpty()) "未选择" else "已选 ${newWorkKbs.size} 个知识库",
                                color = if (newWorkKbs.isEmpty()) ChMuted else ChAccent, fontSize = 12.sp
                            )
                        },
                        modifier = Modifier.clickable { showNewKbPicker = true }
                    )
                    ListItem(
                        headlineContent = { Text("设定工作助理") },
                        supportingContent = {
                            Text(
                                newAssistantName.ifBlank { if (newAssistantPrompt.isNotBlank()) "已设定提示词" else "未设置（可选：规定输出格式/模板）" },
                                color = if (newAssistantName.isBlank()) ChMuted else ChAccent, fontSize = 12.sp
                            )
                        },
                        modifier = Modifier.clickable {
                            assistantForNew = true
                            assistantAvatarPath = null
                            // 默认携带写作助理提示词（可改可清空）
                            if (newAssistantPrompt.isBlank()) newAssistantPrompt = DEFAULT_ASSISTANT_PROMPT
                            showAssistantEditor = true
                        }
                    )
                    ListItem(
                        headlineContent = { Text("直接对话") },
                        supportingContent = { Text("不引库、不设助理，直接开始", color = ChMuted, fontSize = 12.sp) },
                        modifier = Modifier.clickable {
                            showNewWorkMenu = false
                            createWorkSession(emptyList(), "", "", "", null, newWorkTitle.trim())
                            newWorkTitle = ""
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showNewWorkMenu = false
                    createWorkSession(
                        newWorkKbs, newAssistantName, newAssistantNote, newAssistantPrompt,
                        assistantAvatarPath, newWorkTitle.trim()
                    )
                    newWorkTitle = ""
                }) { Text("开始") }
            },
            dismissButton = { TextButton(onClick = { showNewWorkMenu = false }) { Text("取消") } }
        )
    }

    // ---------- 知识库多选 ----------
    if (showNewKbPicker || showChatKbPicker) {
        AlertDialog(
            onDismissRequest = { showNewKbPicker = false; showChatKbPicker = false },
            title = { Text("引用知识库") },
            text = {
                if (allKbs.isEmpty()) {
                    Text("还没有知识库", color = ChMuted, fontSize = 13.sp)
                } else {
                    Column {
                        allKbs.forEach { kb ->
                            val checked = pickKbs.contains(kb.id)
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    pickKbs = if (checked) pickKbs - kb.id else pickKbs + kb.id
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = checked, onCheckedChange = {
                                    pickKbs = if (checked) pickKbs - kb.id else pickKbs + kb.id
                                })
                                Spacer(Modifier.width(6.dp))
                                Column {
                                    Text(kb.name, color = AppColors.text, fontSize = 14.sp)
                                    Text("TopK ${kb.topK}", color = ChMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (showChatKbPicker) {
                        val row = current
                        if (row != null) {
                            val json = org.json.JSONArray(pickKbs.toList()).toString()
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    db.updateChatKbs(row.id, if (pickKbs.isEmpty()) null else json)
                                }
                                val fresh = withContext(Dispatchers.IO) { db.chatById(row.id) }
                                if (fresh != null) current = fresh
                            }
                        }
                        showChatKbPicker = false
                    } else {
                        newWorkKbs = pickKbs.toList()
                        showNewKbPicker = false
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showNewKbPicker = false; showChatKbPicker = false }) { Text("取消") }
            }
        )
    }

    // ---------- 工作助理编辑 ----------
    if (showAssistantEditor) {
        AlertDialog(
            onDismissRequest = { showAssistantEditor = false },
            title = { Text(if (assistantForNew) "设定工作助理" else "工作助理") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AvatarCircle(assistantAvatarPath, 48.dp, fallbackIcon = Icons.Filled.Person)
                        Spacer(Modifier.width(10.dp))
                        TextButton(onClick = { assistantAvatarPicker.launch("image/*") }) {
                            Text("选择头像", color = ChAccent, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = newAssistantName,
                        onValueChange = { newAssistantName = it },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newAssistantNote,
                        onValueChange = { newAssistantNote = it },
                        label = { Text("备注") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newAssistantPrompt,
                        onValueChange = { newAssistantPrompt = it },
                        label = { Text("提示词（规定输出格式/模板）") },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val row = current
                    if (!assistantForNew && row != null) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                db.updateChatAssistant(
                                    row.id,
                                    newAssistantName.ifBlank { null },
                                    newAssistantNote.ifBlank { null },
                                    newAssistantPrompt.ifBlank { null },
                                    assistantAvatarPath
                                )
                            }
                            val fresh = withContext(Dispatchers.IO) { db.chatById(row.id) }
                            if (fresh != null) current = fresh
                        }
                    }
                    showAssistantEditor = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showAssistantEditor = false }) { Text("取消") } }
        )
    }

    // ---------- 音色 ----------
    if (showVoicePicker) {
        val voices = remember(showVoicePicker) { TtsVoices.list(context) }
        val currentVoiceId = current?.let { voiceFor(it)?.id }
        AlertDialog(
            onDismissRequest = { showVoicePicker = false },
            title = { Text("朗读音色") },
            text = {
                if (voices.isEmpty()) {
                    Text("还没有导入声音包", color = ChMuted, fontSize = 13.sp)
                } else {
                    Column {
                        voices.forEach { v ->
                            ListItem(
                                headlineContent = { Text(v.name) },
                                supportingContent = {
                                    if (v.id == currentVoiceId) Text("当前使用", color = ChAccent, fontSize = 11.sp)
                                },
                                modifier = Modifier.clickable {
                                    val row = current
                                    if (row != null) {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { db.updateChatVoice(row.id, v.id) }
                                            val fresh = withContext(Dispatchers.IO) { db.chatById(row.id) }
                                            if (fresh != null) current = fresh
                                        }
                                    }
                                    showVoicePicker = false
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showVoicePicker = false }) { Text("关闭") } }
        )
    }

    // 新建对话时的世界书选择（可多选）


    // 世界书选择
    if (showLorePicker) {
        var books by remember { mutableStateOf(listOf<Lorebook>()) }
        LaunchedEffect(Unit) { books = withContext(Dispatchers.IO) { db.listLorebooks() } }
        AlertDialog(
            onDismissRequest = { showLorePicker = false },
            containerColor = AppColors.card,
            titleContentColor = AppColors.text,
            textContentColor = AppColors.text,
            title = { Text("世界书") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "绑定的集锦会拼进系统提示词，只对当前会话生效。",
                        color = AppColors.muted, fontSize = 11.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    if (books.isEmpty()) {
                        Text("还没有世界书，去「角色卡/世界书」页新建。", color = AppColors.muted, fontSize = 12.sp)
                    }
                    ListItem(
                        headlineContent = { Text("不使用世界书", color = if (current?.lorebookId == null) AppColors.accent else AppColors.text) },
                        modifier = Modifier.clickable {
                            scope.launch {
                                val row = current
                                if (row != null) {
                                    withContext(Dispatchers.IO) { db.setChatLorebook(row.id, null) }
                                    current = row.copy(lorebookId = null)
                                    currentLoreName = null
                                }
                                showLorePicker = false
                            }
                        }
                    )
                    books.forEach { b ->
                        ListItem(
                            headlineContent = {
                                Text(b.name, color = if (current?.lorebookId == b.id) AppColors.accent else AppColors.text)
                            },
                            modifier = Modifier.clickable {
                                scope.launch {
                                    val row = current
                                    if (row != null) {
                                        withContext(Dispatchers.IO) { db.setChatLorebook(row.id, b.id) }
                                        current = row.copy(lorebookId = b.id)
                                        currentLoreName = b.name
                                    }
                                    showLorePicker = false
                                }
                            }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showLorePicker = false }) { Text("关闭") } }
        )
    }

    // 聊天背景（每个会话单独）
    if (showBgDialog) {
        AlertDialog(
            onDismissRequest = { showBgDialog = false },
            containerColor = AppColors.card,
            titleContentColor = AppColors.text,
            textContentColor = AppColors.text,
            title = { Text("聊天背景") },
            text = {
                Column {
                    Text(
                        if (chatBg == null) "当前会话使用默认背景" else "仅对这个会话生效",
                        color = AppColors.muted, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    ListItem(
                        headlineContent = { Text("选择图片") },
                        modifier = Modifier.clickable {
                            showBgDialog = false
                            bgPicker.launch("image/*")
                        }
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("模糊 ${bgBlur.toInt()}", color = AppColors.text, fontSize = 12.sp)
                    Slider(
                        value = bgBlur,
                        onValueChange = {
                            bgBlur = it
                            current?.let { row ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        db.updateChatBg(row.id, chatBg, it.toInt(), bgDim.toInt())
                                    }
                                }
                            }
                        },
                        valueRange = 0f..25f,
                        steps = 24
                    )
                    Text("暗化 ${bgDim.toInt()}%", color = AppColors.text, fontSize = 12.sp)
                    Slider(
                        value = bgDim,
                        onValueChange = {
                            bgDim = it
                            current?.let { row ->
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        db.updateChatBg(row.id, chatBg, bgBlur.toInt(), it.toInt())
                                    }
                                }
                            }
                        },
                        valueRange = 0f..85f,
                        steps = 16
                    )
                    if (chatBg != null) {
                        ListItem(
                            headlineContent = { Text("恢复默认背景", color = AppColors.danger) },
                            modifier = Modifier.clickable {
                                scope.launch {
                                    val row = current
                                    if (row != null) {
                                        withContext(Dispatchers.IO) {
                                            chatBg?.let { runCatching { java.io.File(it).delete() } }
                                            db.updateChatBg(row.id, null, 8, 60)
                                        }
                                    }
                                    chatBg = null
                                    bgBlur = 8f
                                    bgDim = 60f
                                    showBgDialog = false
                                }
                            }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showBgDialog = false }) { Text("关闭") } }
        )
    }

    // 重命名会话
    renameTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("修改名称") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("会话名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = renameText.trim()
                    if (n.isNotEmpty()) {
                        scope.launch {
                            withContext(Dispatchers.IO) { db.renameChat(row.id, n) }
                            renameTarget = null
                            reloadLists()
                            if (current?.id == row.id) {
                                val fresh = withContext(Dispatchers.IO) { db.chatById(row.id) }
                                if (fresh != null) current = fresh
                            }
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } }
        )
    }

    // 删除确认
    deleteTarget?.let { m ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除消息") },
            text = { Text(if (m.role == "assistant") "只删除这一条，还是连同它之后的对话一起删除？" else "确定删除这条消息？") },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                deleteAttachFiles(listOf(m))
                                db.deleteMessage(m.id)
                                db.log("delete", m.id, null, "only")
                            }
                            deleteTarget = null
                            reloadMessages()
                        }
                    }) { Text("仅本条") }
                    if (m.role == "assistant") {
                        TextButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    // P2-1：只删树中后代，同问题的其它回答保留
                                    val victims = db.messages(m.chatId, 10000)
                                    val tree = runCatching { db.deleteMessageTree(m.chatId, m.id) }.getOrDefault(0)
                                    if (tree > 0) deleteAttachFiles(victims.filter { true })
                                    db.log("delete", m.id, null, "tree=$tree")
                                }
                                deleteTarget = null
                                reloadMessages()
                            }
                        }) { Text("连同之后的回复", color = AppColors.danger) }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }

    // 切换模型
    modelPickerFor?.let { m ->
        val cloudModel = db.getSetting("llm_model") ?: "deepseek-chat"
        AlertDialog(
            onDismissRequest = { modelPickerFor = null },
            title = { Text("用哪个模型重答") },
            text = {
                Column {
                    Text("仅本次生效，不改会话默认模型", color = ChMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    ListItem(
                        headlineContent = { Text("本地 Qwen2.5-1.5B") },
                        supportingContent = { Text("端侧推理，离线可用", color = ChMuted, fontSize = 11.sp) },
                        modifier = Modifier.clickable {
                            modelPickerFor = null
                            regenerate(m, "local", "switch_model")
                        }
                    )
                    ListItem(
                        headlineContent = { Text("云端：$cloudModel") },
                        supportingContent = { Text("按模型选择页的配置调用", color = ChMuted, fontSize = 11.sp) },
                        modifier = Modifier.clickable {
                            modelPickerFor = null
                            regenerate(m, "cloud", "switch_model")
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { modelPickerFor = null }) { Text("取消") } }
        )
    }

    // 编辑
    editTarget?.let { m ->
        var text by remember(m.id) { mutableStateOf(m.content) }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("修改消息") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 5,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            db.updateMessageContent(m.id, text.trim())
                            db.log("edit", m.id, m.model)
                        }
                        editTarget = null
                        reloadMessages()
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text("取消") } }
        )
    }
}

private data class AttachItem(val type: String, val path: String, val name: String, val chars: Int)

private fun parseAttachments(raw: String?): List<AttachItem> = runCatching {
    val arr = org.json.JSONArray(raw ?: "[]")
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        AttachItem(o.optString("type", "image"), o.optString("path"), o.optString("name"), o.optInt("chars"))
    }.filter { it.path.isNotBlank() }
}.getOrDefault(emptyList())

@Composable
private fun AttachmentViews(raw: String?) {
    val items = remember(raw) { parseAttachments(raw) }
    items.forEach { item ->
        if (item.type == "file") {
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppColors.cardAlt)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Description, "文档", tint = AppColors.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name.ifBlank { "文件" }, color = AppColors.text, fontSize = 12.sp, maxLines = 1)
                    Text(
                        if (item.chars > 0) "已读取 ${item.chars} 字" else "未提取到文字",
                        color = AppColors.muted, fontSize = 10.sp
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        } else {
            val bmp = remember(item.path) {
                runCatching { android.graphics.BitmapFactory.decodeFile(item.path) }.getOrNull()
            }
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .widthIn(max = 160.dp)
                        .heightIn(max = 200.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/**
 * 去掉“动作描写”：圆括号（全/半角）、方头括号【】、星号对 *...*。
 * 保留「」与英文引号内的台词。支持嵌套括号（反复剥离最内层）。
 */
/** 工作助理默认提示词（新建工作任务时预填，用户可改可清空）。 */
const val DEFAULT_ASSISTANT_PROMPT =
    "你是一名克制的写作助理。保留用户原本的语气，不编造事实。写作前先梳理目标、读者和结构；引用知识库事实时保留来源，并明确标注仍需核实的内容"

/** TTS 文本：优先读引号里的台词；没有台词才退回“去动作”全文。 */
fun ttsText(raw: String, readActions: Boolean): String {
    if (readActions) return raw
    val quoted = Regex("""[“"「『]([^”"」』]{1,300})[”"」』]""")
        .findAll(raw)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotBlank() }
        .toList()
    if (quoted.isNotEmpty()) return quoted.joinToString("。")
    return stripAction(raw)
}

fun stripAction(raw: String): String {
    var t = raw
    // 1) 星号动作：*smiles*，允许跨行（最多 400 字）
    t = t.replace(Regex("""(?s)\*[^*]{1,400}\*"""), " ")
    // 2) 括号：含中文才视为动作；纯数字/公式/英文保持原样
    val pair = Regex("""（[^（）]*）|\([^()]*\)|【[^【】]*】""")
    // 说明性关键词：出现这些就当正常括号保留（如“（详见附录）”“（第 3 页）”）
    val explainWords = listOf("详见", "参见", "见附录", "参考", "例如", "示例", "注：", "注:", "如下图", "如图", "表格", "第", "页", "年", "月", "日", "版本", "以上", "以下", "共", "约", "合计", "单位", "步骤")
    // 动作特征词：出现这些更像舞台指示
    val actionWords = listOf("笑", "愣", "叹", "皱眉", "抬眼", "低头", "点头", "摇头", "沉默", "转身", "伸手", "伸手", "看", "说", "道", "轻声", "低声", "喃", "咳", "耸", "摊手", "抱", "揉", "握住", "后退", "靠近", "顿", "停顿", "想了想", "无奈", "苦笑", "摇头")
    fun repl(m: MatchResult): String {
        val v = m.value
        if (v.startsWith("【")) return " "
        val body = v.substring(1, v.length - 1).trim()
        if (body.isEmpty()) return " "
        val hasCjk = body.any { it.code in 0x4E00..0x9FFF }
        if (!hasCjk) return v
        if (explainWords.any { body.contains(it) }) return v
        val looksAction = actionWords.any { body.contains(it) } || body.length <= 12
        return if (looksAction) " " else v
    }
    var changed = true
    var guard = 0
    while (changed && guard < 6) {
        guard++
        val next = pair.replace(t) { repl(it) }
        changed = next != t
        t = next
    }
    // 3) 清理多余空白
    t = t.replace(Regex("""[ \t]{2,}"""), " ")
        .replace(Regex("""\n{3,}"""), "\n\n")
    return t.trim()
}

private fun hintFor(topK: Int, count: Int, topScore: Float, mode: String): String? {
    if (mode != MODE_WORK) return null
    return when {
        topK > 5 && count >= topK -> "已返回 $count 条片段，可调低检索精度"
        topK < 5 && (count < topK || topScore < 0.45f) -> "命中内容不足，建议调高检索精度"
        else -> null
    }
}

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Icon(icon, label, tint = ChMuted, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun DrawerAction(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = ChAccent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, color = AppColors.text, fontSize = 15.sp)
    }
}

@Composable
private fun DrawerGroupTitle(text: String) {
    Text(
        text, color = ChMuted, fontSize = 12.sp,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp)
    )
}

@Composable
private fun DrawerEmpty(text: String) {
    Text(
        text, color = AppColors.muted, fontSize = 12.sp,
        modifier = Modifier.padding(start = 20.dp, top = 2.dp, bottom = 6.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerHistory(
    title: String,
    active: Boolean,
    isBranch: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (active) AppColors.cardAlt else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = if (isBranch) 38.dp else 20.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (isBranch) "↳ " + title.removePrefix("↳ ") else title,
            color = if (active) AppColors.text else AppColors.text,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Delete, "删除", tint = AppColors.muted, modifier = Modifier.size(15.dp))
        }
    }
}
