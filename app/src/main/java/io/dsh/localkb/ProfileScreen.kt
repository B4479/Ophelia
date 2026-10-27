package io.dsh.localkb

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val PBg: Color get() = AppColors.bg
private val PCard: Color get() = AppColors.card
private val PAccent: Color get() = AppColors.accent
private val PMuted: Color get() = AppColors.muted

object ProfileStore {
    const val KEY_NAME = "profile_name"
    const val KEY_BIO = "profile_bio"
    const val KEY_IDENTITY = "profile_identity"
    const val KEY_AVATAR = "profile_avatar"
    const val KEY_ENABLED = "profile_identity_enabled"

    const val MAX_NAME = 16
    const val MAX_BIO = 200
    const val MAX_IDENTITY = 800
    const val DEFAULT_NAME = "用户"

    fun isEnabled(db: Db): Boolean = (db.getSetting(KEY_ENABLED) ?: "1") == "1"

    fun displayName(db: Db): String =
        db.getSetting(KEY_NAME)?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME

    /** 把用户身份追加到 system prompt 末尾；没有内容时原样返回。 */
    fun augment(db: Db, base: String): String {
        val block = promptBlock(db)
        return if (block.isBlank()) base else base + "\n\n" + block
    }

    /** 拼成注入 system prompt 的用户侧信息；为空则返回空串。 */
    fun promptBlock(db: Db): String {
        if (!isEnabled(db)) return ""
        val name = db.getSetting(KEY_NAME).orEmpty().trim()
        val identity = db.getSetting(KEY_IDENTITY).orEmpty().trim()
        if (name.isEmpty() && identity.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("[用户身份]")
        if (name.isNotEmpty()) sb.append("\n昵称：").append(name)
        if (identity.isNotEmpty()) sb.append("\n身份设定：").append(identity)
        return sb.toString()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(db: Db, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(db.getSetting(ProfileStore.KEY_NAME).orEmpty()) }
    var bio by remember { mutableStateOf(db.getSetting(ProfileStore.KEY_BIO).orEmpty()) }
    var identity by remember { mutableStateOf(db.getSetting(ProfileStore.KEY_IDENTITY).orEmpty()) }
    var avatar by remember { mutableStateOf(db.getSetting(ProfileStore.KEY_AVATAR)) }
    var enabled by remember { mutableStateOf(ProfileStore.isEnabled(db)) }
    var msg by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.filesDir, "avatars")
                    if (!dir.exists()) dir.mkdirs()
                    val f = File(dir, "profile_${System.currentTimeMillis()}.jpg")
                    context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input)
                        f.outputStream().use { input.copyTo(it) }
                    }
                    f.absolutePath
                }.getOrNull()
            }
            if (path != null) avatar = path
        }
    }

    fun save() {
        db.setSetting(ProfileStore.KEY_NAME, name.trim())
        db.setSetting(ProfileStore.KEY_BIO, bio.trim())
        db.setSetting(ProfileStore.KEY_IDENTITY, identity.trim())
        db.setSetting(ProfileStore.KEY_AVATAR, avatar ?: "")
        db.setSetting(ProfileStore.KEY_ENABLED, if (enabled) "1" else "0")
        msg = "已保存，之后的对话会带上这些信息"
    }

    fun reset() {
        avatar?.let { runCatching { File(it).delete() } }
        name = ""
        bio = ""
        identity = ""
        avatar = null
        enabled = true
        db.setSetting(ProfileStore.KEY_NAME, "")
        db.setSetting(ProfileStore.KEY_BIO, "")
        db.setSetting(ProfileStore.KEY_IDENTITY, "")
        db.setSetting(ProfileStore.KEY_AVATAR, "")
        db.setSetting(ProfileStore.KEY_ENABLED, "1")
        msg = "已重置个人主页（角色卡 / 模型 / 音色不受影响）"
        confirmReset = false
    }

    Scaffold(
        containerColor = PBg,
        topBar = {
            TopAppBar(
                title = { Text("个人主页", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = AppColors.text)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PBg, titleContentColor = AppColors.text)
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 头像
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                AvatarCircle(avatar, 96.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { pickAvatar.launch("image/*") }) {
                        Text("上传头像", color = PAccent, fontSize = 13.sp)
                    }
                    TextButton(onClick = {
                        avatar?.let { runCatching { File(it).delete() } }
                        avatar = null
                    }) {
                        Text("重置为默认头像", color = PMuted, fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= ProfileStore.MAX_NAME) name = it },
                label = { Text("昵称（必填，2–16 字）") },
                placeholder = { Text(ProfileStore.DEFAULT_NAME, color = PMuted) },
                singleLine = true,
                supportingText = { Text("${name.length}/${ProfileStore.MAX_NAME}", color = PMuted, fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = bio,
                onValueChange = { if (it.length <= ProfileStore.MAX_BIO) bio = it },
                label = { Text("个人简介（可选）") },
                placeholder = { Text("只用于你自己的资料页，不会发给模型", color = PMuted) },
                minLines = 3,
                maxLines = 6,
                supportingText = { Text("${bio.length}/${ProfileStore.MAX_BIO}", color = PMuted, fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = identity,
                onValueChange = { if (it.length <= ProfileStore.MAX_IDENTITY) identity = it },
                label = { Text("身份设定") },
                placeholder = { Text("告诉 AI「你是谁」：职业、背景、偏好、说话习惯……", color = PMuted) },
                minLines = 5,
                maxLines = 10,
                supportingText = { Text("${identity.length}/${ProfileStore.MAX_IDENTITY}", color = PMuted, fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "身份设定会在每次对话附带发送给模型，告诉 AI「你是谁」；和角色卡（AI 的设定）相互独立。",
                color = AppColors.muted, fontSize = 11.sp
            )

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("启用身份设定", color = AppColors.text, fontSize = 15.sp)
                    Text("关闭后，身份设定与昵称都不再注入请求", color = PMuted, fontSize = 11.sp)
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            msg?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = PAccent, fontSize = 12.sp)
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { save() },
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.success, contentColor = AppColors.text)
                ) { Text("保存修改") }
                OutlinedButton(onClick = { if (!confirmReset) confirmReset = true }) {
                    Text("重置", color = AppColors.danger)
                }
            }
            if (confirmReset) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("确认清空个人主页？", color = AppColors.danger, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { reset() }) { Text("确认", color = AppColors.danger) }
                    TextButton(onClick = { confirmReset = false }) { Text("取消", color = PMuted) }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}
