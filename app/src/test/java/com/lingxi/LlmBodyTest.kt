package com.lingxi

import com.lingxi.data.ChatImage
import com.lingxi.data.ChatMessage
import com.lingxi.data.buildChatBody
import com.lingxi.data.functions.ToolSpec
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmBodyTest {

    @Test
    fun `纯文本消息用字符串 content`() {
        val body = buildChatBody(
            model = "m",
            messages = listOf(ChatMessage("system", "你是灵犀"), ChatMessage("user", "你好")),
            temperature = 0.7,
            tools = null,
        )
        val messages = body["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("你好", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `带图片消息走多模态 content 数组`() {
        val body = buildChatBody(
            model = "m",
            messages = listOf(
                ChatMessage(
                    "user", "看看屏幕上这个",
                    images = listOf(ChatImage("image/jpeg", "QUJD")),
                ),
            ),
            temperature = 0.7,
            tools = null,
        )
        val content = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(2, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,QUJD",
            content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `tools 序列化为 function 协议`() {
        val spec = ToolSpec("set_alarm", "设闹钟", """{"type":"object","properties":{}}""")
        val body = buildChatBody("m", listOf(ChatMessage("user", "q")), 0.7, listOf(spec))
        val tool = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)
        assertEquals("set_alarm", tool["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }
}
