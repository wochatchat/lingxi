package com.lingxi

import com.lingxi.data.memory.MemoryPrompts
import com.lingxi.data.memory.ProfileGate
import com.lingxi.data.memory.parseDailySummary
import com.lingxi.data.memory.parseProfileExtraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPromptsTest {

    // ---- buildSystemPrompt ----

    @Test
    fun `无记忆时只回基底`() {
        val p = MemoryPrompts.buildSystemPrompt("基底人格", emptyList(), emptyList())
        assertEquals("基底人格", p)
    }

    @Test
    fun `画像与摘要分层拼接`() {
        val p = MemoryPrompts.buildSystemPrompt(
            "基底",
            listOf("用户名叫阿哲"),
            listOf("2026-10-08" to "- 要点一"),
        )
        assertTrue(p.startsWith("基底"))
        assertTrue(p.contains("【关于用户的长期记忆】"))
        assertTrue(p.contains("- 用户名叫阿哲"))
        assertTrue(p.contains("【近期对话摘要】"))
        assertTrue(p.contains("2026-10-08: - 要点一"))
        // 顺序：画像在摘要前
        assertTrue(p.indexOf("长期记忆") < p.indexOf("近期对话摘要"))
    }

    // ---- ProfileGate ----

    @Test
    fun `门控命中语音编辑信号`() {
        listOf(
            "以后别叫我用户，叫我阿哲",
            "以后结论先说",
            "记住我不喝咖啡",
            "我是产品经理",
            "从现在开始说话简短点",
            "别再问我了",
            "我习惯早上跑步",
        ).forEach { assertTrue("应命中: $it", ProfileGate.shouldExtract(it)) }
    }

    @Test
    fun `门控放过普通对话`() {
        listOf(
            "今天天气怎么样",
            "帮我设个明天八点的闹钟",
            "晚饭吃什么好",
            "再见",
        ).forEach { assertFalse("不应命中: $it", ProfileGate.shouldExtract(it)) }
    }

    // ---- parseProfileExtraction ----

    @Test
    fun `提取解析 - 正常内容`() {
        assertEquals("用户名叫阿哲", parseProfileExtraction("用户名叫阿哲"))
        assertEquals("用户希望结论先行", parseProfileExtraction("「用户希望结论先行」"))
        assertEquals("用户不喝咖啡", parseProfileExtraction("“用户不喝咖啡。”"))
    }

    @Test
    fun `提取解析 - NONE 与空回复`() {
        assertNull(parseProfileExtraction("NONE"))
        assertNull(parseProfileExtraction("none"))
        assertNull(parseProfileExtraction("无"))
        assertNull(parseProfileExtraction(""))
        assertNull(parseProfileExtraction("   "))
    }

    @Test
    fun `提取解析 - 多行只取首行且限长`() {
        assertEquals("用户名叫阿哲", parseProfileExtraction("用户名叫阿哲\n额外解释"))
        val long = parseProfileExtraction("长".repeat(200))
        assertEquals(120, long!!.length)
    }

    // ---- 每日摘要解析 ----

    @Test
    fun `摘要解析 - 去掉行首符号`() {
        val s = parseDailySummary("- 要点一\n• 要点二\n* 要点三")
        assertEquals("要点一\n要点二\n要点三", s)
        assertEquals(3, s.lines().size)
    }

    @Test
    fun `摘要解析 - 超长截断`() {
        val text = (1..100).joinToString("\n") { "第${it}条要点" }
        assertTrue(parseDailySummary(text).length <= 600)
    }

    @Test
    fun `摘要解析 - 空回复返回空串`() {
        assertEquals("", parseDailySummary("  \n "))
    }
}
