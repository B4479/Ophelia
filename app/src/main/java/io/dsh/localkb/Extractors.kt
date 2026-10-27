package io.dsh.localkb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import java.io.File
import java.util.zip.ZipFile

/**
 * 多格式文本抽取。统一返回 (位置序号, 文本)。
 * 位置语义随格式不同：PDF/OCR=页，EPUB=章，DOCX/TXT/MD/HTML=节，CSV=行块，PPTX=页。
 */
object Extract {

    private val TEXT_EXT = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "log", "srt", "vtt",
        "py", "kt", "java", "js", "ts", "c", "cpp", "h", "cs", "go", "rs", "rb", "php", "sh", "sql", "html", "htm"
    )

    fun kindOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> "pdf"
            "epub" -> "epub"
            "docx" -> "docx"
            "pptx" -> "pptx"
            "xlsx" -> "xlsx"
            "rtf" -> "rtf"
            "png", "jpg", "jpeg", "webp", "bmp" -> "image"
            in TEXT_EXT -> if (ext == "csv" || ext == "tsv") "csv" else "text"
            else -> "unknown"
        }
    }

    fun isSupported(name: String): Boolean {
        val k = kindOf(name)
        return k != "unknown" && k != "pdf" || k == "pdf"
    }

    fun extract(context: Context, file: File, kind: String): List<Pair<Int, String>> {
        return when (kind) {
            "pdf" -> PdfImport.extractFile(context, file).getOrThrow()
            "epub" -> epub(file)
            "docx" -> docx(file)
            "pptx" -> pptx(file)
            "xlsx" -> xlsx(file)
            "rtf" -> rtf(file)
            "image" -> ocrImage(context, file)
            "csv" -> csv(file)
            else -> text(file)
        }
    }

    // ---------------- 纯文本 ----------------

    private fun decode(bytes: ByteArray): String {
        // BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        val utf8 = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
        val replacement = utf8?.count { it == '\uFFFD' } ?: Int.MAX_VALUE
        if (utf8 != null && replacement * 100 / maxOf(1, utf8.length) < 1) return utf8
        // 常见中文编码回退
        return runCatching { String(bytes, charset("GBK")) }.getOrElse { utf8 ?: "" }
    }

    private fun blocks(text: String, target: Int = 1800): List<Pair<Int, String>> {
        val clean = text.replace("\r\n", "\n").replace('\r', '\n')
        val paras = clean.split(Regex("\n{1,}")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<Pair<Int, String>>()
        val sb = StringBuilder()
        var idx = 1
        for (p in paras) {
            if (sb.length + p.length > target && sb.isNotEmpty()) {
                out.add(idx++ to sb.toString())
                sb.setLength(0)
            }
            sb.append(p).append('\n')
        }
        if (sb.isNotEmpty()) out.add(idx to sb.toString())
        return out
    }

    private fun text(file: File): List<Pair<Int, String>> = blocks(decode(file.readBytes()))

    private fun csv(file: File): List<Pair<Int, String>> {
        val lines = decode(file.readBytes()).split('\n')
        val out = ArrayList<Pair<Int, String>>()
        val header = lines.firstOrNull().orEmpty()
        var idx = 1
        val sb = StringBuilder(header).append('\n')
        for (i in 1 until lines.size) {
            sb.append(lines[i]).append('\n')
            if (sb.length > 1800) {
                out.add(idx++ to sb.toString())
                sb.setLength(0)
            }
        }
        if (sb.isNotBlank()) out.add(idx to sb.toString())
        return out
    }

    // ---------------- 压缩包/办公 ----------------

    private fun stripXml(s: String): String {
        var t = s
            .replace(Regex("(?i)<w:p[ >]"), "\n<w:p ")
            .replace(Regex("(?i)</w:p>"), "\n")
            .replace(Regex("(?i)<a:p>"), "\n")
            .replace(Regex("(?i)</a:p>"), "\n")
            .replace(Regex("(?s)<[^>]+>"), " ")
        t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&#160;", " ")
        return t.replace(Regex("[ \t]{2,}"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun docx(file: File): List<Pair<Int, String>> {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return emptyList()
            val xml = zip.getInputStream(entry).use { it.readBytes() }.toString(Charsets.UTF_8)
            return blocks(stripXml(xml))
        }
    }

    private fun pptx(file: File): List<Pair<Int, String>> {
        ZipFile(file).use { zip ->
            val slides = zip.entries().toList()
                .filter { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                .sortedBy { Regex("\\d+").find(it.name)?.value?.toIntOrNull() ?: 0 }
            val out = ArrayList<Pair<Int, String>>()
            slides.forEachIndexed { i, e ->
                val xml = zip.getInputStream(e).use { it.readBytes() }.toString(Charsets.UTF_8)
                val t = stripXml(xml)
                if (t.isNotBlank()) out.add((i + 1) to t)
            }
            return out
        }
    }

    private fun xlsx(file: File): List<Pair<Int, String>> {
        ZipFile(file).use { zip ->
            val out = ArrayList<Pair<Int, String>>()
            var idx = 1
            val entries = zip.entries().toList()
                .filter { it.name.startsWith("xl/") && it.name.endsWith(".xml") }
                .sortedBy { it.name }
            for (e in entries) {
                val xml = runCatching { zip.getInputStream(e).use { it.readBytes() }.toString(Charsets.UTF_8) }
                    .getOrDefault("")
                val t = stripXml(xml)
                if (t.isNotBlank()) out.add(idx++ to t)
            }
            return out
        }
    }

    private fun epub(file: File): List<Pair<Int, String>> {
        ZipFile(file).use { zip ->
            // container.xml -> opf
            val container = zip.getEntry("META-INF/container.xml")?.let {
                zip.getInputStream(it).use { s -> s.readBytes() }.toString(Charsets.UTF_8)
            }.orEmpty()
            val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1)
                ?: zip.entries().toList().firstOrNull { it.name.endsWith(".opf") }?.name
                ?: return emptyList()
            val opf = zip.getEntry(opfPath)?.let {
                zip.getInputStream(it).use { s -> s.readBytes() }.toString(Charsets.UTF_8)
            } ?: return emptyList()
            val base = opfPath.substringBeforeLast('/', "")
            val manifest = HashMap<String, String>()
            Regex("<item\\b[^>]*>").findAll(opf).forEach { m ->
                val tag = m.value
                val id = Regex("id=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)
                val href = Regex("href=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)
                if (id != null && href != null) manifest[id] = href
            }
            val order = Regex("<itemref\\b[^>]*idref=\"([^\"]+)\"").findAll(opf).mapNotNull { it.groupValues.getOrNull(1) }.toList()
            val out = ArrayList<Pair<Int, String>>()
            var idx = 1
            val hrefs = order.mapNotNull { manifest[it] }.ifEmpty { manifest.values.toList() }
            for (href in hrefs) {
                val path = if (base.isEmpty()) href else "$base/$href"
                val cleanPath = path.replace("\\", "/").replace("./", "")
                val e = zip.getEntry(cleanPath) ?: zip.getEntry(href) ?: continue
                val html = zip.getInputStream(e).use { it.readBytes() }.toString(Charsets.UTF_8)
                val title = Regex("(?i)<title>(.*?)</title>").find(html)?.groupValues?.get(1)?.trim()
                val body = stripXml(
                    html.replace(Regex("(?is)<script.*?</script>"), " ")
                        .replace(Regex("(?is)<style.*?</style>"), " ")
                )
                if (body.isBlank()) continue
                val text = if (!title.isNullOrBlank()) "【$title】\n$body" else body
                out.add(idx++ to text)
            }
            return out
        }
    }

    private fun rtf(file: File): List<Pair<Int, String>> {
        var s = decode(file.readBytes())
        s = s.replace(Regex("\\\\'[0-9a-fA-F]{2}"), "")
            .replace(Regex("\\\\[a-zA-Z]+-?\\d* ?"), " ")
            .replace(Regex("[{}]"), " ")
            .replace(Regex("\\s{2,}"), " ")
        return blocks(s)
    }

    // ---------------- OCR ----------------

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private fun ocrBitmap(bmp: Bitmap): String = runCatching {
        val image = InputImage.fromBitmap(bmp, 0)
        Tasks.await(recognizer.process(image)).text
    }.getOrDefault("")

    private fun ocrImage(context: Context, file: File): List<Pair<Int, String>> {
        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return emptyList()
        val scale = 1600f / maxOf(1, bmp.width)
        val scaled = if (scale > 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        } else bmp
        val text = ocrBitmap(scaled)
        return if (text.isBlank()) emptyList() else listOf(1 to text)
    }

    /** 扫描版 PDF：逐页渲染后 OCR。 */
    fun ocrPdf(context: Context, file: File, fromPage: Int = 1): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val start = (fromPage - 1).coerceIn(0, maxOf(0, renderer.pageCount - 1))
                for (i in start until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    try {
                        val w = 1400
                        val h = (w * page.height / page.width.toFloat()).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val text = ocrBitmap(bmp)
                        bmp.recycle()
                        if (text.isNotBlank()) out.add((i + 1) to text)
                    } finally {
                        page.close()
                    }
                }
            }
        }
        return out
    }
}
