package io.dsh.localkb

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 后台导入：页级检查点，进程被杀后从 pages_done 继续，不从头再来。
 * 输入：docId（doc 表里必须已有 src_path 与 pages_done）。
 */
class ImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val docId = inputData.getLong("docId", -1L)
        if (docId <= 0) return@withContext Result.failure()

        val db = Db(applicationContext)
        val doc = db.docById(docId) ?: return@withContext Result.failure()
        val src = doc.srcPath?.let { File(it) }
        if (src == null || !src.isFile) {
            db.setDocStatus(docId, "failed")
            return@withContext Result.failure()
        }
        if (doc.status == "ready") return@withContext Result.success()

        val startPage = doc.pagesDone.coerceAtLeast(0)
        var chars = doc.chars
        var processed = startPage
        try {
            db.setDocStatus(docId, "parsing")
            var pages = Extract.extract(applicationContext, src, doc.kind)
            // 扫描版 PDF：没有文字层就回退到 OCR
            if (doc.kind == "pdf" && pages.sumOf { it.second.length } < 20) {
                db.setDocStatus(docId, "ocr")
                pages = Extract.ocrPdf(applicationContext, src)
            }
            db.setDocPages(docId, pages.size)
            if (pages.isEmpty()) {
                db.updateProgress(docId, 0, 0, "empty")
                return@withContext Result.success()
            }
            val totalChars = pages.sumOf { it.second.length }
            // 只有真正没抽到字才算 empty（短文档也要能导入）
            if (pages.isEmpty() || totalChars == 0) {
                db.updateProgress(docId, pages.size, totalChars, "empty")
                return@withContext Result.success()
            }

            // 从检查点重来：先清掉未完成的页之后的所有片段，避免半页重复
            if (startPage > 0) db.deleteChunksFrom(docId, startPage + 1)

            val embedder = EmbedderHolder.get(applicationContext)

            for ((pageNo, text) in pages) {
                if (pageNo <= startPage) continue
                // 删除知识库后 Worker 可能仍在运行：每页提交前确认文档仍存在
                if (db.docById(docId) == null) {
                    Log.i("ImportWorker", "doc $docId 已被删除，停止导入")
                    return@withContext Result.success()
                }
                if (isStopped) {
                    db.updateProgress(docId, pageNo - 1, chars, "paused")
                    return@withContext Result.retry()
                }
                val chunks = Chunker.split(text)
                if (chunks.isNotEmpty()) {
                    val items = ArrayList<Triple<Int, String, FloatArray>>(chunks.size)
                    for (c in chunks) items.add(Triple(pageNo, c, embedder.embed(c)))
                    db.addChunks(doc.kbId, docId, items)
                }
                chars += text.length
                processed = pageNo
                db.updateProgress(docId, pageNo, chars, "embedding")
                setProgress(workDataOf("pagesDone" to pageNo, "pages" to pages.size))
            }

            db.updateProgress(docId, pages.size, chars, "ready")
            Log.i("ImportWorker", "done doc=$docId pages=${pages.size} chars=$chars")
            Result.success()
        } catch (t: Throwable) {
            Log.e("ImportWorker", "failed doc=$docId at page=$processed", t)
            // P2-5：永久性错误直接失败，其它最多重试 3 次
            val permanent = t is java.io.FileNotFoundException ||
                t is java.util.zip.ZipException ||
                t is IllegalArgumentException ||
                t is OutOfMemoryError
            if (permanent || runAttemptCount >= 3) {
                db.updateProgress(docId, processed, chars, "failed")
                Result.failure()
            } else {
                db.setDocStatus(docId, "paused")
                Result.retry()
            }
        }
    }
}

object ImportQueue {

    /** 入队：复制原件到私有目录，插 doc 行，再交给 WorkManager。 */
    fun enqueue(context: Context, db: Db, kbId: Long, uri: Uri, displayName: String): Long {
        val kind = Extract.kindOf(displayName)
        val ext = displayName.substringAfterLast('.', "bin").lowercase().take(8)
        val dir = File(context.filesDir, "docs")
        if (!dir.exists()) dir.mkdirs()
        // P2-4：先复制到临时文件，成功后才建 doc 行，避免幽灵文档
        val tmp = File(dir, "tmp_${System.currentTimeMillis()}.$ext")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "无法读取文件" }
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw t
        }
        val docId = db.addDoc(kbId, displayName, kind)
        val dest = File(dir, "doc_$docId.$ext")
        if (!tmp.renameTo(dest)) {
            runCatching { tmp.delete() }
            db.deleteDocRow(docId)
            error("临时文件重命名失败")
        }
        db.setDocSource(docId, dest.absolutePath)
        enqueueWork(context, docId)
        return docId
    }

    fun enqueueWork(context: Context, docId: Long) {
        val req = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(workDataOf("docId" to docId))
            .addTag("import")
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("import_$docId", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** 启动时恢复所有未完成的导入。 */
    fun resumeAll(context: Context, db: Db) {
        val work = WorkManager.getInstance(context)
        for (d in db.pendingDocs()) {
            if (work.getWorkInfosForUniqueWork("import_${d.id}").get().any { !it.state.isFinished }) continue
            enqueueWork(context, d.id)
        }
    }

    /** 删除知识库前取消该库全部导入任务。 */
    fun cancelAllForKb(context: Context, db: Db, kbId: Long) {
        db.listDocs(kbId).forEach { d ->
            runCatching {
                WorkManager.getInstance(context).cancelUniqueWork("import_${d.id}")
            }
        }
    }

    fun cancel(context: Context, docId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork("import_$docId")
    }

    fun deleteDoc(context: Context, db: Db, doc: DocItem) {
        cancel(context, doc.id)
        doc.srcPath?.let { runCatching { File(it).delete() } }
        db.deleteDoc(doc.id)
    }
}
