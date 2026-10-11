package com.lingxi.data.functions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlCardTest {

    // ---------- parse ----------

    @Test
    fun `parse 正常参数`() {
        val c = HtmlCard.parse("""{"title":"本周步数","html":"<div>hi</div>"}""")
        assertNotNull(c)
        assertEquals("本周步数", c!!.title)
        assertEquals("<div>hi</div>", c.html)
    }

    @Test
    fun `parse 缺 html 或空白返回 null`() {
        assertNull(HtmlCard.parse("""{"title":"t"}"""))
        assertNull(HtmlCard.parse("""{"title":"t","html":"   "}"""))
        assertNull(HtmlCard.parse("not json"))
        assertNull(HtmlCard.parse("{}"))
    }

    @Test
    fun `parse 缺标题回落默认`() {
        assertEquals("卡片", HtmlCard.parse("""{"html":"<p>x</p>"}""")!!.title)
    }

    // ---------- sanitize：危险元素与外链剥除 ----------

    @Test
    fun `外链脚本整块移除`() {
        val out = HtmlCard.sanitize("""<script src="https://evil.com/x.js"></script><p>ok</p>""")
        assertFalse(out.contains("evil.com"))
        assertTrue(out.contains("<p>ok</p>"))
    }

    @Test
    fun `内联脚本与样式保留`() {
        val html = """<style>p{color:#fff}</style><script>document.title='x'</script><p onclick="hi()">ok</p>"""
        val out = HtmlCard.sanitize(html)
        assertTrue(out.contains("<style>"))
        assertTrue(out.contains("<script>document.title='x'</script>"))
        assertTrue(out.contains("<p onclick"))
    }

    @Test
    fun `iframe 等嵌入元素移除`() {
        val out = HtmlCard.sanitize("""<iframe src="https://a.com"></iframe><object data="x"></object><p>ok</p>""")
        assertFalse(out.contains("<iframe"))
        assertFalse(out.contains("<object"))
        assertTrue(out.contains("<p>ok</p>"))
    }

    @Test
    fun `属性级 http 外链剥除`() {
        val out = HtmlCard.sanitize("""<img src="https://tracker.com/p.png" alt="a"><a href="https://x.com">go</a>""")
        assertFalse(out.contains("tracker.com"))
        assertFalse(out.contains("https://x.com"))
        assertTrue(out.contains("<img  alt=\"a\">"))
    }

    @Test
    fun `css url 外链剥除`() {
        val out = HtmlCard.sanitize("""<div style="background:url(https://img.com/a.png) no-repeat">x</div>""")
        assertFalse(out.contains("img.com"))
        assertTrue(out.contains("no-repeat"))
    }

    @Test
    fun `data URI 保留`() {
        val out = HtmlCard.sanitize("""<img src="data:image/png;base64,AAAA">""")
        assertTrue(out.contains("data:image/png;base64,AAAA"))
    }

    // ---------- CSP 注入与包装 ----------

    @Test
    fun `完整文档 head 内注入 CSP`() {
        val out = HtmlCard.sanitize("<html><head><title>t</title></head><body>ok</body></html>")
        assertTrue(out.contains(HtmlCard.CSP_META))
        assertTrue(out.indexOf(HtmlCard.CSP_META) < out.indexOf("<title>"))
    }

    @Test
    fun `纯片段包装完整文档并注入 CSP`() {
        val out = HtmlCard.sanitize("<div>hi</div>")
        assertTrue(out.startsWith("<!DOCTYPE html>"))
        assertTrue(out.contains(HtmlCard.CSP_META))
        assertTrue(out.contains("<body><div>hi</div></body>"))
    }

    @Test
    fun `超长内容截断`() {
        val longHtml = "<p>" + "a".repeat(HtmlCard.MAX_HTML_CHARS + 1000) + "</p>"
        val out = HtmlCard.sanitize(longHtml)
        // 截断后长度不超过上限 + 包装注入的固定前缀
        assertTrue(out.length <= HtmlCard.MAX_HTML_CHARS + 400)
    }
}
