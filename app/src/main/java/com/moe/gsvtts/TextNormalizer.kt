package com.moe.gsvtts

/**
 * 朗读前的文本规范化：
 * - 阿拉伯数字 → 中文读法（年份按位读，整数按十百千万，支持小数/百分号）
 * - 全角数字/字母转半角
 * - 中文标点 → 模型符号表里存在的标点（否则会被前端丢掉，失去停顿）
 *
 * 注：GPT-SoVITS 的符号表只有 ASCII 标点和音素，所以「，。！？」要映射成 ",.！?"。
 */
object TextNormalizer {

    private val digits = charArrayOf('零', '一', '二', '三', '四', '五', '六', '七', '八', '九')
    private val units = charArrayOf(' ', '十', '百', '千')

    private val PUNCT_MAP = mapOf(
        '，' to ',', '、' to ',', '；' to ',', '：' to ',',
        '。' to '.', '！' to '!', '？' to '?', '…' to '…',
        '—' to '-', '－' to '-', '～' to '-', '~' to '-',
        '（' to ',', '）' to ',', '(' to ',', ')' to ',',
        '【' to ',', '】' to ',', '《' to ',', '》' to ',',
        '“' to ',', '”' to ',', '「' to ',', '」' to ',', '『' to ',', '』' to ',',
        '‘' to ',', '’' to ',', '"' to ',', '\'' to ',',
    )

    fun normalize(text: String): String {
        val sb = StringBuilder(text.length + 16)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isDigit() || (c in '０'..'９') -> {
                    var j = i
                    val num = StringBuilder()
                    while (j < text.length && (text[j].isDigit() || text[j] in '０'..'９')) {
                        num.append(if (text[j] in '０'..'９') ('0' + (text[j] - '０')) else text[j]); j++
                    }
                    var frac = ""
                    if (j < text.length && (text[j] == '.' || text[j] == '．') && j + 1 < text.length && text[j + 1].isDigit()) {
                        val f = StringBuilder()
                        j++
                        while (j < text.length && text[j].isDigit()) { f.append(text[j]); j++ }
                        frac = f.toString()
                    }
                    val isPercent = j < text.length && text[j] == '%'
                    if (isPercent) j++
                    val next = if (j < text.length) text[j] else ' '
                    val cn = if (next == '年' && num.length == 4) readDigits(num.toString())
                    else readInteger(num.toString())
                    if (isPercent) sb.append("百分之")
                    sb.append(cn)
                    if (frac.isNotEmpty()) {
                        sb.append('点')
                        sb.append(readDigits(frac))
                    }
                    i = j
                }
                c in PUNCT_MAP -> { sb.append(PUNCT_MAP[c]); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private fun readDigits(s: String): String = s.map { digits[it - '0'] }.joinToString("")

    private fun readInteger(s: String): String {
        if (s.isEmpty()) return ""
        val n = s.trimStart('0')
        if (n.isEmpty()) return "零"
        if (n.length > 8) return readDigits(n) // 太长就按位读
        val sb = StringBuilder()
        var zero = false
        for ((idx, ch) in n.withIndex()) {
            val d = ch - '0'
            val unitIdx = n.length - 1 - idx
            if (d == 0) {
                zero = true
            } else {
                if (zero && sb.isNotEmpty()) sb.append('零')
                zero = false
                if (!(d == 1 && unitIdx == 1 && idx == 0)) sb.append(digits[d])
                if (unitIdx in 1..3) sb.append(units[unitIdx])
            }
            if (unitIdx == 4 && sb.isNotEmpty()) sb.append('万')
        }
        return sb.toString()
    }
}
