package com.lingxi.data

/**
 * 流式句切分器：LLM delta 累积，遇句末标点即切出完整句（TTS 预读播报的基座）。
 * 纯状态类，无 Android 依赖，可单测。
 *
 * 规则：
 * - 句末标点：。！？!?；;… 及换行
 * - 缓冲超过 [forceSplitChars] 仍无标点 → 强制切出（防 LLM 不吐标点导致永不播报）
 * - [flush] 吐出残余（turn 结束时调用）
 */
class SentenceSplitter(private val forceSplitChars: Int = 60) {
    private val buf = StringBuilder()

    /** 喂入一段增量，返回本次切出的完整句（可能 0 到多个） */
    fun feed(delta: String): List<String> {
        buf.append(delta)
        val out = mutableListOf<String>()
        var last = 0
        for (i in buf.indices) {
            if (buf[i] in SENTENCE_ENDS) { out.add(buf.substring(last, i + 1)); last = i + 1 }
        }
        buf.delete(0, last)
        // 强制切：无标点但已过长，定长切出，余量继续累积
        while (buf.length >= forceSplitChars) {
            out.add(buf.substring(0, forceSplitChars))
            buf.delete(0, forceSplitChars)
        }
        return out
    }

    /** 结束时吐出残余未成句内容 */
    fun flush(): List<String> {
        val rest = buf.toString()
        buf.clear()
        return if (rest.isBlank()) emptyList() else listOf(rest)
    }

    companion object {
        val SENTENCE_ENDS = charArrayOf('。', '！', '？', '!', '?', '；', ';', '…', '\n')
    }
}

/**
 * 把 LLM 输出转成「适合口播」的纯文本：去掉 Markdown 标记（TTS 会念出星号井号）。
 * 纯函数可测。轻量处理，不做完整 Markdown 解析。
 */
fun stripMarkdownForSpeech(raw: String): String {
    var s = raw
    s = s.replace(Regex("```[\\s\\S]*?```"), " 代码略。")   // 代码块口播无意义
    s = s.replace(Regex("!\\[.*?]\\(.*?\\)"), "")            // 图片
    s = s.replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")     // 链接留文字
    s = s.replace(Regex("[#*_`>~]+"), "")                    // 行内符号
    s = s.replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "")
    s = s.replace(Regex("^\\s*\\d+\\.\\s+", RegexOption.MULTILINE), "")
    return s.replace(Regex("\\s+\\n"), "\n").trim()
}
