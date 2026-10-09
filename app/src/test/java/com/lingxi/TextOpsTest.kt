package com.lingxi

import com.lingxi.data.SentenceSplitter
import com.lingxi.data.stripMarkdownForSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextOpsTest {

    @Test
    fun `feed 切出句末标点`() {
        val sp = SentenceSplitter()
        assertEquals(listOf("你好。"), sp.feed("你好。今天天气"))
        assertEquals("今天天气", sp.flush()[0])
    }

    @Test
    fun `feed 换行也切句`() {
        val sp = SentenceSplitter()
        assertEquals(listOf("第一行\n"), sp.feed("第一行\n第二行"))
        assertEquals(listOf("第二行"), sp.flush())
    }

    @Test
    fun `无标点超过上限强制切出`() {
        val sp = SentenceSplitter(forceSplitChars = 10)
        val out = sp.feed("这是很长很长很长很长很长很长很长很长的一句话")
        assertEquals(2, out.size)
        assertEquals(10, out[0].length)
        assertEquals(10, out[1].length)
        assertEquals(listOf("句话"), sp.flush())
    }

    @Test
    fun `flush 吐出残余并清空`() {
        val sp = SentenceSplitter()
        sp.feed("没有标点的尾巴")
        assertEquals(1, sp.flush().size)
        assertEquals(emptyList<String>(), sp.flush()) // 二次 flush 为空
    }

    @Test
    fun `英文标点同样切分`() {
        val sp = SentenceSplitter()
        assertEquals(listOf("Hello!", " How are you?"), sp.feed("Hello! How are you?"))
    }

    @Test
    fun `stripMarkdown 去粗体井号行内代码`() {
        val cleaned = stripMarkdownForSpeech("# 标题\n**加粗** 和 `code` 的混合\n- 列表项\n1. 有序项")
        assertFalse(cleaned.contains("*"))
        assertFalse(cleaned.contains("#"))
        assertFalse(cleaned.contains("`"))
        assertFalse(cleaned.contains("- "))
        assertTrue(cleaned.contains("标题"))
    }

    @Test
    fun `stripMarkdown 链接保留文字`() {
        val cleaned = stripMarkdownForSpeech("看[这个文档](https://x.com)吧")
        assertEquals("看这个文档吧", cleaned.replace(" ", ""))
    }

    @Test
    fun `stripMarkdown 代码块整体替换`() {
        val cleaned = stripMarkdownForSpeech("这样写：\n```kotlin\nval a = 1\n```\n完成")
        assertFalse(cleaned.contains("val a"))
        assertFalse(cleaned.contains("```"))
    }
}
