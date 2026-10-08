package com.lingxi

import com.lingxi.data.parseSseLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {

    @Test
    fun parsesDeltaContent() {
        val line = """data: {"choices":[{"delta":{"content":"你好"},"finish_reason":null}]}"""
        val chunk = parseSseLine(line)!!
        assertEquals("你好", chunk.deltaText)
        assertNull(chunk.finishReason)
        assertFalse(chunk.done)
    }

    @Test
    fun parsesFinishReason() {
        val line = """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}"""
        val chunk = parseSseLine(line)!!
        assertEquals("stop", chunk.finishReason)
    }

    @Test
    fun parsesDone() {
        val chunk = parseSseLine("data: [DONE]")!!
        assertTrue(chunk.done)
    }

    @Test
    fun ignoresNonDataLines() {
        // 空行 / 注释 / 事件行 / 其它
        assertNull(parseSseLine(""))
        assertNull(parseSseLine(": keep-alive"))
        assertNull(parseSseLine("event: message"))
        assertNull(parseSseLine("retry: 3000"))
    }

    @Test
    fun ignoresMalformedData() {
        assertNull(parseSseLine("data: {broken json"))
        assertNull(parseSseLine("data: "))
    }

    @Test
    fun handlesMissingChoices() {
        // 网关兼容性：无 choices 字段不炸
        val chunk = parseSseLine("""data: {"object":"chat.completion.chunk"}""")
        assertTrue(chunk != null && !chunk.done && chunk.deltaText == null)
    }

    @Test
    fun handlesReasoningFieldOnly() {
        // 部分模型只有 reasoning_content 无 content
        val line = """data: {"choices":[{"delta":{"reasoning_content":"思考中"}}]}"""
        val chunk = parseSseLine(line)!!
        assertNull(chunk.deltaText)
    }
}
