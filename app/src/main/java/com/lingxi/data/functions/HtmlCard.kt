package com.lingxi.data.functions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * R14 generative_html 原语：LLM 产出的单文件 HTML 富卡片的解析与消毒。
 *
 * 安全边界（PRD 风险登记 §2.3）：
 * - 只允许内联 CSS/JS 与 data: 资源，所有网络引用（http/https/协议相对）一律剥除；
 * - 移除可嵌入外部内容的元素（iframe/object/embed/link/base/form）；
 * - 注入 CSP meta 兜底（default-src 'none'），即使消毒漏网也无法外联；
 * - 长度上限防注入超长内容拖垮 WebView。
 */
object HtmlCard {

    /** 长度上限（字符） */
    const val MAX_HTML_CHARS = 80_000

    /** 兜底 CSP：内联样式/脚本放行，data: 图片/字体放行，其余全禁 */
    const val CSP_META =
        "<meta http-equiv=\"Content-Security-Policy\" " +
            "content=\"default-src 'none'; style-src 'unsafe-inline'; " +
            "script-src 'unsafe-inline'; img-src data:; font-src data:;\">"

    /** 解析后的富卡片内容 */
    data class Content(val title: String, val html: String)

    /** 解析 generative_html 工具参数（{"title":..,"html":..}），失败返回 null */
    fun parse(argsJson: String): Content? = runCatching {
        val obj = Json.parseToJsonElement(argsJson.trim()).jsonObject
        val html = obj["html"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return@runCatching null
        if (html.isBlank()) return@runCatching null
        val title = obj["title"]?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotBlank() }
            ?: "卡片"
        Content(title, html)
    }.getOrNull()

    /**
     * 消毒并归一化为完整文档：剥外部引用与危险元素 → 截断 → 注入 CSP。
     * 输入可以是完整 HTML 文档或 body 片段。
     */
    fun sanitize(html: String): String {
        var h = stripExternalRefs(html.trim())
        if (h.length > MAX_HTML_CHARS) h = h.take(MAX_HTML_CHARS)
        return return wrapWithCsp(h)
    }

    /** 剥除外部网络引用与危险元素（保持内联 CSS/JS 可用） */
    private fun stripExternalRefs(input: String): String {
        var h = input
        // 危险容器整块移除（含内容）
        for (tag in listOf("iframe", "object", "embed", "form")) {
            h = h.replace(
                Regex("<$tag\\b[\\s\\S]*?</$tag\\s*>", RegexOption.IGNORE_CASE), "")
        }
        // 自闭合/空容器（link 引外部样式、base 重定向等）
        for (tag in listOf("iframe", "object", "embed", "form", "link", "base")) {
            h = h.replace(Regex("<$tag\\b[^>]*/?\\s*>", RegexOption.IGNORE_CASE), "")
        }
        // 外链脚本整块移除（含内容）；内联 <script> 保留
        h = h.replace(
            Regex("<script\\b[^>]*\\bsrc\\s*=[^>]*>[\\s\\S]*?</script\\s*>", RegexOption.IGNORE_CASE), "")
        h = h.replace(
            Regex("<script\\b[^>]*\\bsrc\\s*=[^>]*/?\\s*>", RegexOption.IGNORE_CASE), "")
        // 属性级外链：src/href/poster 指向 http(s)、协议相对或 javascript:
        h = h.replace(
            Regex("""(?:src|href|poster)\s*=\s*("\s*(?:https?:)?//[^"]*"|'\s*(?:https?:)?//[^']*'|\s*(?:https?:)?//[^\s>]+)""",
                RegexOption.IGNORE_CASE), "")
        h = h.replace(
            Regex("""href\s*=\s*("?|')\s*javascript:[^"'>]*\1""", RegexOption.IGNORE_CASE), "")
        // CSS url() 外链（背景图/字体）
        h = h.replace(
            Regex("""url\(\s*['"]?\s*(?:https?:)?//[^)'"]*['"]?\s*\)""", RegexOption.IGNORE_CASE), "none")
        return h
    }

    /** 注入 CSP meta：有 <head> 插其内；有 <html> 补 head；纯片段包完整文档 */
    private fun wrapWithCsp(html: String): String {
        val headRegex = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)
        return when {
            headRegex.containsMatchIn(html) ->
                headRegex.find(html)?.let { html.replaceRange(it.range, it.value + CSP_META) }
                    ?: html
            Regex("<html\\b[^>]*>", RegexOption.IGNORE_CASE).containsMatchIn(html) ->
                html.replace(Regex("<html\\b[^>]*>", RegexOption.IGNORE_CASE)) { m ->
                    m.value + "<head>$CSP_META</head>"
                }
            else ->
                "<!DOCTYPE html><html><head>$CSP_META</head><body>$html</body></html>"
        }
    }
}
