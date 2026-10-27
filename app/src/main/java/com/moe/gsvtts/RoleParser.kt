package com.moe.gsvtts

/**
 * 把一段正文切成「旁白 / 对白」片段，并用「已知角色名」识别对白说话人。
 *
 * 为什么用已知名字而不是通用正则：通用正则会把「笑着说：」当成名字，
 * 准确率很低。实际朗读场景里角色名是确定的（就是已安装的声音名 + 别名），
 * 所以直接做名字词典匹配，命中率更高、误判更少；没命中的对白交给声音池分配。
 */
object RoleParser {

    data class Segment(val text: String, val role: String?, val dialogue: Boolean)

    private val OPEN = charArrayOf('“', '「', '『')
    private val CLOSE = charArrayOf('”', '」', '』')

    /** 名字前后允许的副词/动词，用于确认名字确实是在指说话人。 */
    private val SPEECH_HINT = Regex(
        "(说|道|问|答|喊|叫|吼|笑|嘟囔|开口|回答|反问|补充|继续|低声|大声|轻声|冷冷|喃喃|叹|骂|想|看|转过头|点头|摇头)"
    )

    fun parse(text: String, names: Set<String> = emptySet()): List<Segment> {
        val raw = ArrayList<Segment>()
        val buf = StringBuilder()
        var inQuote = false
        var closeChar = ' '
        var bufIsDialogue = false

        fun flush() {
            val t = buf.toString().trim()
            if (t.isNotEmpty()) raw.add(Segment(t, null, bufIsDialogue))
            buf.setLength(0)
        }

        for (c in text) {
            if (!inQuote) {
                if (c in OPEN) {
                    flush()
                    inQuote = true
                    bufIsDialogue = true
                    closeChar = when (c) {
                        '“' -> '”'
                        '「' -> '」'
                        else -> '』'
                    }
                } else {
                    if (bufIsDialogue) { flush(); bufIsDialogue = false }
                    buf.append(c)
                }
            } else {
                if (c == closeChar || c in CLOSE) {
                    flush()
                    inQuote = false
                    bufIsDialogue = false
                } else {
                    buf.append(c)
                }
            }
        }
        flush()

        val out = ArrayList<Segment>(raw.size)
        for (i in raw.indices) {
            val seg = raw[i]
            if (!seg.dialogue) {
                out.add(seg)
                continue
            }
            val before = raw.getOrNull(i - 1)?.takeIf { !it.dialogue }?.text?.takeLast(24).orEmpty()
            var after = raw.getOrNull(i + 1)?.takeIf { !it.dialogue }?.text?.take(24).orEmpty()
            // 后文如果以冒号结尾，说明它在引出一句新的对白，不能拿来判断当前这句的说话人
            if (after.trimEnd().endsWith("：") || after.trimEnd().endsWith(":")) after = ""
            out.add(seg.copy(role = findName(before, after, names)))
        }

        val merged = ArrayList<Segment>()
        for (s in out) {
            val last = merged.lastOrNull()
            if (last != null && last.dialogue == s.dialogue && last.role == s.role) {
                merged[merged.size - 1] = last.copy(text = last.text + " " + s.text)
            } else merged.add(s)
        }
        return merged
    }

    /** 在前后文里找已知角色名；命中返回名字，否则 null。 */
    fun findName(before: String, after: String, names: Set<String>): String? {
        if (names.isEmpty()) return null
        // 越靠近引号的名字优先级越高：先看前文尾部，再看后文头部
        for (n in names.sortedByDescending { it.length }) {
            val i = before.lastIndexOf(n)
            if (i >= 0) return n
        }
        for (n in names.sortedByDescending { it.length }) {
            if (after.contains(n)) return n
        }
        return null
    }

    /** 通用兜底：从「XX说/道/问」里猜名字（仅在没有名字词典时使用）。 */
    fun guessSpeaker(before: String, after: String): String? {
        val m = Regex("([\\u4e00-\\u9fa5]{2,4})\\s*(?=说|道|问|答|喊|叫|吼|笑)").find(before) ?: return null
        val cand = m.groupValues[1]
        return if (SPEECH_HINT.containsMatchIn(cand)) null else cand
    }
}
