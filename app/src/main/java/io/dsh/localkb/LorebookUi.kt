package io.dsh.localkb

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 世界书集锦列表（嵌在“角色卡/世界书”页的第二个 Tab 里）。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LorebookListInline(
    db: Db,
    refreshKey: Int,
    onOpen: (Lorebook) -> Unit,
    onCreate: () -> Unit,
) {
    var books by remember { mutableStateOf(listOf<Lorebook>()) }
    var counts by remember { mutableStateOf(mapOf<Long, Int>()) }
    var renameTarget by remember { mutableStateOf<Lorebook?>(null) }
    var deleteTarget by remember { mutableStateOf<Lorebook?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        withContext(Dispatchers.IO) {
            val list = db.listLorebooks()
            books = list
            counts = list.associate { it.id to db.listLoreEntries(it.id).size }
        }
    }
    LaunchedEffect(refreshKey) { reload() }

    Column(Modifier.fillMaxSize()) {
        var stickyOn by remember { mutableStateOf(GenParamsStore.stickyEnabled(db)) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("命中后本会话保持注入", color = AppColors.text, fontSize = 13.sp)
                Text("关闭则每轮重新判断（关键词/向量）", color = AppColors.muted, fontSize = 10.sp)
            }
            Switch(checked = stickyOn, onCheckedChange = {
                stickyOn = it
                scope.launch { withContext(Dispatchers.IO) { GenParamsStore.setSticky(db, it) } }
            })
        }
        Spacer(Modifier.height(6.dp))
        if (books.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有世界书集锦。\n点右下角 + 新建，然后在集锦里添加世界观条目。",
                    color = AppColors.muted, fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(books) { b ->
                    var expanded by remember(b.id) { mutableStateOf(false) }
                    Column(
                        Modifier.fillMaxWidth()
                            .background(AppColors.card, RoundedCornerShape(14.dp))
                            .border(1.dp, AppColors.divider, RoundedCornerShape(14.dp))
                            .combinedClickable(
                                onClick = { onOpen(b) },
                                onLongClick = { expanded = !expanded }
                            )
                            .padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.AutoStories, null, tint = AppColors.accent, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(b.name, color = AppColors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text("${counts[b.id] ?: 0} 个条目", color = AppColors.muted, fontSize = 11.sp)
                            }
                            if (expanded) {
                                IconButton(onClick = { renameTarget = b }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Edit, "重命名", tint = AppColors.muted, modifier = Modifier.size(16.dp))
                                }
                                IconButton(onClick = { deleteTarget = b }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Filled.Delete, "删除", tint = AppColors.danger, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(60.dp)) }
            }
        }
    }

    renameTarget?.let { b ->
        var text by remember(b.id) { mutableStateOf(b.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = AppColors.card,
            titleContentColor = AppColors.text,
            textContentColor = AppColors.text,
            title = { Text("重命名集锦") },
            text = {
                OutlinedTextField(text, { text = it }, label = { Text("名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank()) {
                        scope.launch {
                            withContext(Dispatchers.IO) { db.renameLorebook(b.id, text.trim()) }
                            renameTarget = null
                            reload()
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } }
        )
    }

    deleteTarget?.let { b ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = AppColors.card,
            titleContentColor = AppColors.text,
            textContentColor = AppColors.text,
            title = { Text("删除集锦") },
            text = { Text("删除「${b.name}」及其所有条目？\n使用它的会话会自动解除绑定，历史消息不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val bound = withContext(Dispatchers.IO) { db.countChatsUsingLorebook(b.id) }
                        withContext(Dispatchers.IO) { db.deleteLorebook(b.id) }
                        deleteTarget = null
                        reload()
                        if (bound > 0) {
                            android.widget.Toast.makeText(
                                context, "已自动解绑 $bound 个会话", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }) { Text("删除", color = AppColors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

/** 集锦详情：增删改世界观条目。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LorebookDetailScreen(db: Db, book: Lorebook, onBack: () -> Unit) {
    var entries by remember { mutableStateOf(listOf<LoreEntry>()) }
    var editing by remember { mutableStateOf<LoreEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<LoreEntry?>(null) }
    var bulkOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    suspend fun reload() {
        entries = withContext(Dispatchers.IO) { db.listLoreEntries(book.id) }
    }
    LaunchedEffect(book.id) { reload() }

    Scaffold(
        containerColor = AppColors.bg,
        topBar = {
            TopAppBar(
                title = { Text(book.name, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AppColors.text)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.bg, titleContentColor = AppColors.text)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }, containerColor = AppColors.accent) {
                Icon(Icons.Filled.Add, "新增条目", tint = AppColors.onAccent)
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "这些条目会在每次生成时注入模型。修改即时生效；删除或改动不会改写已有聊天记录。",
                    color = AppColors.muted, fontSize = 11.sp, modifier = Modifier.weight(1f)
                )
                Box {
                    TextButton(onClick = { bulkOpen = true }) {
                        Text("批量", color = AppColors.accent, fontSize = 12.sp)
                    }
                    DropdownMenu(expanded = bulkOpen, onDismissRequest = { bulkOpen = false }) {
                        DropdownMenuItem(text = { Text("全部启用") }, onClick = {
                            bulkOpen = false
                            scope.launch {
                                withContext(Dispatchers.IO) { db.bulkUpdateLoreEntries(book.id, enabled = true) }
                                reload()
                            }
                        })
                        DropdownMenuItem(text = { Text("全部停用") }, onClick = {
                            bulkOpen = false
                            scope.launch {
                                withContext(Dispatchers.IO) { db.bulkUpdateLoreEntries(book.id, enabled = false) }
                                reload()
                            }
                        })
                        DropdownMenuItem(text = { Text("全部设为永久") }, onClick = {
                            bulkOpen = false
                            scope.launch {
                                withContext(Dispatchers.IO) { db.bulkUpdateLoreEntries(book.id, mode = MODE_ALWAYS) }
                                reload()
                            }
                        })
                        DropdownMenuItem(text = { Text("全部设为关键词") }, onClick = {
                            bulkOpen = false
                            scope.launch {
                                withContext(Dispatchers.IO) { db.bulkUpdateLoreEntries(book.id, mode = MODE_KEYWORD) }
                                reload()
                            }
                        })
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("还没有条目，点右下角 + 添加", color = AppColors.muted, fontSize = 13.sp)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(entries) { e ->
                        Row(
                            Modifier.fillMaxWidth()
                                .background(AppColors.card, RoundedCornerShape(12.dp))
                                .border(1.dp, AppColors.divider, RoundedCornerShape(12.dp))
                                .clickable { editing = e }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(e.title.ifBlank { "（未命名条目）" }, color = AppColors.text, fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium, maxLines = 1)
                                ModeBadge(e.mode)
                                Text(e.content, color = AppColors.muted, fontSize = 11.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Switch(
                                checked = e.enabled,
                                onCheckedChange = { v ->
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            db.updateLoreEntry(e.id, e.title, e.content, v, e.mode, e.keys, e.emb, e.keys2, e.selective, e.caseSensitive, e.scanDepth)
                                        }
                                        reload()
                                    }
                                }
                            )
                            IconButton(onClick = { deleteTarget = e }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Delete, "删除", tint = AppColors.danger, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    item { Spacer(Modifier.height(60.dp)) }
                }
            }
        }
    }

    if (adding || editing != null) {
        LoreEntryDialog(
            existing = editing,
            onDismiss = { adding = false; editing = null },
            onSave = { title, content, enabled, mode, keys, keys2, selective, caseSensitive, scanDepth ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val old = editing
                        val needEmb = mode == MODE_VECTOR
                        val emb = if (needEmb) runCatching {
                            EmbedderHolder.get(context).embed(title + " " + content)
                        }.getOrNull() else null
                        if (old == null) {
                            db.addLoreEntry(book.id, title, content, mode, keys, emb, keys2, selective, caseSensitive, scanDepth)
                        } else {
                            db.updateLoreEntry(old.id, title, content, enabled, mode, keys, emb, keys2, selective, caseSensitive, scanDepth)
                        }
                    }
                    adding = false
                    editing = null
                    reload()
                }
            }
        )
    }

    deleteTarget?.let { e ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = AppColors.card,
            titleContentColor = AppColors.text,
            textContentColor = AppColors.text,
            title = { Text("删除条目") },
            text = { Text("删除「${e.title}」？正在使用该集锦的会话不受影响，历史消息保留。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { db.deleteLoreEntry(e.id) }
                        deleteTarget = null
                        reload()
                    }
                }) { Text("删除", color = AppColors.danger) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun LoreEntryDialog(
    existing: LoreEntry?,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean, String, String?, String?, Boolean, Boolean, Int) -> Unit,
) {
    var title by remember(existing) { mutableStateOf(existing?.title ?: "") }
    var content by remember(existing) { mutableStateOf(existing?.content ?: "") }
    var enabled by remember(existing) { mutableStateOf(existing?.enabled ?: true) }
    var mode by remember(existing) { mutableStateOf(existing?.mode ?: MODE_ALWAYS) }
    var keys by remember(existing) { mutableStateOf(existing?.keys ?: "") }
    var keys2 by remember(existing) { mutableStateOf(existing?.keys2 ?: "") }
    var selective by remember(existing) { mutableStateOf(existing?.selective ?: false) }
    var caseSensitive by remember(existing) { mutableStateOf(existing?.caseSensitive ?: false) }
    var scanDepth by remember(existing) { mutableStateOf(existing?.scanDepth ?: 0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.card,
        titleContentColor = AppColors.text,
        textContentColor = AppColors.text,
        title = { Text(if (existing == null) "新增世界观条目" else "编辑条目") },
        text = {
            Column {
                OutlinedTextField(
                    title, { title = it },
                    label = { Text("标题（如：魔法体系、势力关系）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    content, { content = it },
                    label = { Text("内容") },
                    minLines = 4, maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("触发方式", color = AppColors.muted, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                ModeSelector(mode) { mode = it }
                if (mode == MODE_KEYWORD) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        keys, { keys = it },
                        label = { Text("关键词（逗号分隔，如：王都, 艾雷西亚）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (selective) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            keys2, { keys2 = it },
                            label = { Text("次级关键词（也要命中才触发）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("大小写敏感", color = AppColors.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Switch(checked = caseSensitive, onCheckedChange = { caseSensitive = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("需要次级关键词", color = AppColors.muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Switch(checked = selective, onCheckedChange = { selective = it })
                    }
                    Text("扫描范围", color = AppColors.muted, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "当前消息", 3 to "最近 3 条", 6 to "最近 6 条").forEach { (d, label) ->
                            val sel = scanDepth == d
                            Surface(
                                color = if (sel) AppColors.accent.copy(alpha = 0.14f) else AppColors.cardAlt,
                                shape = RoundedCornerShape(10.dp),
                                border = if (sel) androidx.compose.foundation.BorderStroke(1.dp, AppColors.accent) else null,
                                modifier = Modifier.clickable { scanDepth = d }
                            ) {
                                Text(
                                    label,
                                    color = if (sel) AppColors.accent else AppColors.muted,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                    Spacer(Modifier.width(8.dp))
                    Text(if (enabled) "启用（会注入给模型）" else "停用（不注入）", color = AppColors.muted, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val needKeys = mode == MODE_KEYWORD
                    val ok = content.isNotBlank() && !(needKeys && keys.isBlank()) &&
                        !(needKeys && selective && keys2.isBlank())
                    if (ok) {
                        onSave(
                            title.trim(), content.trim(), enabled, mode,
                            keys.trim().ifBlank { null }, keys2.trim().ifBlank { null },
                            selective && needKeys, caseSensitive, scanDepth
                        )
                    }
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 新建集锦输入框。 */
@Composable
fun NewLorebookDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.card,
        titleContentColor = AppColors.text,
        textContentColor = AppColors.text,
        title = { Text("新建世界书集锦") },
        text = {
            OutlinedTextField(
                name, { name = it },
                label = { Text("集锦名称（如：艾雷西亚大陆）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name.trim()) }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 本会话内已命中的条目（粘性），chatId -> entryIds。 */
object LoreSticky {
    private val map = HashMap<Long, MutableSet<Long>>()
    fun hit(chatId: Long, ids: Collection<Long>) {
        map.getOrPut(chatId) { mutableSetOf() }.addAll(ids)
    }
    fun set(chatId: Long): Set<Long> = map[chatId] ?: emptySet()
    fun clear(chatId: Long) { map.remove(chatId) }
}

private fun splitKeys(raw: String?): List<String> =
    raw.orEmpty().split(',', '，', '、', ';', '；', ' ', '\n')
        .map { it.trim() }.filter { it.isNotEmpty() }

/** 生成时把集锦条目按三种模式拼成提示词（永久 → 关键词 → 向量），上限 2000 字。 */
fun lorebookBlock(
    context: android.content.Context,
    db: Db,
    chatId: Long,
    lorebookId: Long?,
    query: String,
): String? {
    val id = lorebookId ?: return null
    val book = db.lorebookById(id) ?: return null
    val entries = db.listLoreEntries(id, onlyEnabled = true)
    if (entries.isEmpty()) return null

    val stickyOn = GenParamsStore.stickyEnabled(db)
    val sticky = if (stickyOn) LoreSticky.set(chatId) else emptySet()
    val always = entries.filter { it.mode == MODE_ALWAYS }
    // 关键词条目可能配置“扫描最近 N 条”，这里按最大深度一次性取回语料
    val maxDepth = entries.filter { it.mode == MODE_KEYWORD }
        .maxOfOrNull { it.scanDepth } ?: 0
    val recentText = if (maxDepth > 0) {
        runCatching { db.recentMessagesText(chatId, maxDepth) }.getOrDefault("")
    } else ""
    fun corpusFor(e: LoreEntry): String =
        if (e.scanDepth > 0) recentText + "\n" + query else query
    fun hitKeys(text: String, raw: String?, caseSensitive: Boolean): Boolean =
        splitKeys(raw).any { k ->
            if (caseSensitive) text.contains(k) else text.contains(k, ignoreCase = true)
        }

    // 关键词模式：主关键词命中（selective 时还要命中次级关键词）；本会话命中过之后保持（粘性）
    val keywordHits = entries.filter { e ->
        if (e.mode != MODE_KEYWORD) return@filter false
        if (e.id in sticky) return@filter true
        val corpus = corpusFor(e)
        val primary = hitKeys(corpus, e.keys, e.caseSensitive)
        if (!primary) return@filter false
        if (e.selective) hitKeys(corpus, e.keys2, e.caseSensitive) else true
    }
    // 向量模式：命中过的本会话保持（粘性），否则按相似度 >= 0.60 判定
    val vectorEntries = entries.filter { it.mode == MODE_VECTOR }
    val vectorHits = ArrayList<LoreEntry>()
    if (vectorEntries.isNotEmpty()) {
        val qv = runCatching { EmbedderHolder.get(context).embed(query) }.getOrNull()
        if (qv != null) {
            for (e in vectorEntries) {
                if (e.id in sticky) { vectorHits.add(e); continue }
                val ev = e.emb ?: runCatching {
                    EmbedderHolder.get(context).embed(e.title + " " + e.content)
                }.getOrNull()
                if (ev == null) continue
                if (cosine(qv, ev) >= 0.60f) vectorHits.add(e)
            }
        }
    }
    val hits = keywordHits + vectorHits
    LoreSticky.hit(chatId, hits.map { it.id })

    val ordered = always + keywordHits + vectorHits
    if (ordered.isEmpty()) return null
    val sb = StringBuilder("【世界设定 · ${book.name}】\n")
    for (e in ordered) {
        val piece = if (e.title.isBlank()) "- ${e.content}\n" else "- ${e.title}：${e.content}\n"
        if (sb.length + piece.length > 2000) break
        sb.append(piece)
    }
    return sb.toString().trim()
}

private fun cosine(a: FloatArray, b: FloatArray): Float {
    if (a.size != b.size) return 0f
    var dot = 0f; var na = 0f; var nb = 0f
    for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
    val d = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
    return if (d < 1e-9f) 0f else dot / d
}


private fun modeColor(mode: String): androidx.compose.ui.graphics.Color = when (mode) {
    MODE_ALWAYS -> AppColors.success
    MODE_KEYWORD -> AppColors.accent
    else -> AppColors.muted
}

private fun modeLabel(mode: String): String = when (mode) {
    MODE_ALWAYS -> "永久"
    MODE_KEYWORD -> "关键词"
    else -> "向量化"
}

@Composable
private fun ModeBadge(mode: String) {
    Text(modeLabel(mode), color = modeColor(mode), fontSize = 10.sp)
}

/** 三种模式放在同一行，选中项只变色并显示当前模式。 */
@Composable
private fun ModeSelector(current: String, onChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(MODE_ALWAYS, MODE_KEYWORD, MODE_VECTOR).forEach { m ->
            val sel = current == m
            val c = modeColor(m)
            Surface(
                color = if (sel) c.copy(alpha = 0.14f) else AppColors.cardAlt,
                shape = RoundedCornerShape(10.dp),
                border = if (sel) BorderStroke(1.dp, c) else null,
                modifier = Modifier.clickable { onChange(m) }
            ) {
                Text(
                    modeLabel(m),
                    color = if (sel) c else AppColors.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}
