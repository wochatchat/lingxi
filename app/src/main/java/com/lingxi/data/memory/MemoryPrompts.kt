package com.lingxi.data.memory

/**
 * F7 记忆纯逻辑：prompt 拼装 / 语音编辑门控 / LLM 回复解析。
 * 全部无副作用，单测覆盖。
 */
object MemoryPrompts {

    /**
     * 三层注入的 system prompt：
     * 基底人格 + 长期画像（即时生效）+ 近期每日摘要（跨日连续性）。
     */
    fun buildSystemPrompt(
        base: String,
        profileLines: List<String>,
        dailySummaries: List<Pair<String, String>>,
    ): String {
        val sb = StringBuilder(base)
        if (profileLines.isNotEmpty()) {
            sb.append("\n\n【关于用户的长期记忆】请遵守：\n")
            for (line in profileLines) sb.append("- ").append(line).append('\n')
        }
        if (dailySummaries.isNotEmpty()) {
            sb.append("\n\n【近期对话摘要】供延续上下文：\n")
            for ((date, summary) in dailySummaries) sb.append(date).append(": ").append(summary).append('\n')
        }
        return sb.toString()
    }
}

/**
 * 语音编辑记忆门控（S8：'以后结论先说' → 写入画像并即时生效）。
 * 粗筛：命中任意「表达长期偏好/称呼/事实」的信号词才发起 LLM 提取，
 * 控制成本——普通问答不进提取链路。
 */
object ProfileGate {
    private val patterns = listOf(
        Regex("记住"),
        Regex("以后"),
        Regex("从(现在|今)"),
        Regex("别再"),
        Regex("不要再"),
        Regex("叫我"),
        Regex("我的名字"),
        Regex("我(是|叫)"),
        Regex("我(喜欢|讨厌|偏好|不爱|不喝|不吃|习惯)"),
        Regex("结论先说|先说结论|废话少说|长话短说"),
    )

    fun shouldExtract(text: String): Boolean = patterns.any { it.containsMatchIn(text) }
}

/**
 * 解析提取模型的回复为一条画像内容。
 * 模型约定：可提取回一句话；不可提取只回 NONE。防御性清洗各种装饰。
 */
fun parseProfileExtraction(reply: String): String? {
    var s = reply.trim()
    if (s.isEmpty()) return null
    if (s.equals("NONE", ignoreCase = true) || s == "无" || s == "没有" || s == "不适用") return null
    // 去掉常见包装引号与句尾标点
    s = s.trim('"', '\'', '「', '」', '“', '”', '《', '》', ' ', '\n', '。', '，', '.')
    if (s.isEmpty()) return null
    // 若模型输出了多余解释，只取首行
    s = s.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
    return s.take(120)
}

/** 解析每日摘要回复：保留要点行，压平成多行文本（上限 maxChars） */
fun parseDailySummary(reply: String, maxChars: Int = 600): String {
    val lines = reply.lineSequence()
        .map { it.trim().trimStart('-', '•', '·', '*', ' ') }
        .filter { it.isNotBlank() }
        .toList()
    val text = lines.joinToString("\n")
    return if (text.length <= maxChars) text else text.take(maxChars)
}
