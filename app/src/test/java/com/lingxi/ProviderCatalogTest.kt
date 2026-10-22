package com.lingxi

import com.lingxi.data.ProviderCatalog
import com.lingxi.data.ProviderKind
import com.lingxi.data.RemoteCatalog
import com.lingxi.data.RemotePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogTest {

    // ── parse：合法目录 ──
    @Test
    fun parseMapsCatalog() {
        val raw = """
            {"version":2,"presets":[
              {"key":"deepseek","label":"DeepSeek","kind":"CLOUD","baseUrl":"https://api.deepseek.com/v1/","model":"deepseek-chat"},
              {"key":"ollama","label":"Ollama (本地)","kind":"LOCAL","baseUrl":"http://127.0.0.1:11434/v1","model":"qwen2.5:1.5b"}
            ]}
        """.trimIndent()
        val presets = ProviderCatalog.parse(raw)
        assertEquals(2, presets.size)
        assertEquals("DeepSeek", presets[0].label)
        assertEquals(ProviderKind.CLOUD, presets[0].kind)
        assertEquals("https://api.deepseek.com/v1", presets[0].baseUrl) // 尾部斜杠被规整
        assertEquals(ProviderKind.LOCAL, presets[1].kind)
    }

    // ── parse：未知 kind 回落 CLOUD，不整包失败 ──
    @Test
    fun parseUnknownKindFallsBackToCloud() {
        val raw = """
            {"presets":[{"key":"newco","label":"NewCo","kind":"SPACE","baseUrl":"https://api.new.co/v1","model":"x"}]}
        """.trimIndent()
        val presets = ProviderCatalog.parse(raw)
        assertEquals(1, presets.size)
        assertEquals(ProviderKind.CLOUD, presets[0].kind)
    }

    // ── parse：单条非法只丢该条 ──
    @Test
    fun parseDropsInvalidEntries() {
        val raw = """
            {"presets":[
              {"key":"","label":"NoKey","baseUrl":"https://a.com/v1","model":"m"},
              {"key":"nobase","label":"NoBase","baseUrl":"ftp://a.com","model":"m"},
              {"key":"nomodel","label":"NoModel","baseUrl":"https://a.com/v1","model":""},
              {"key":"ok","label":"Ok","baseUrl":"https://a.com/v1","model":"m"}
            ]}
        """.trimIndent()
        val presets = ProviderCatalog.parse(raw)
        assertEquals(1, presets.size)
        assertEquals("ok", presets[0].key)
    }

    // ── parse：整包非法 JSON 返回空列表 ──
    @Test
    fun parseInvalidJsonReturnsEmpty() {
        assertTrue(ProviderCatalog.parse("not json").isEmpty())
        assertTrue(ProviderCatalog.parse("").isEmpty())
        assertTrue(ProviderCatalog.parse("<html>404</html>").isEmpty())
    }

    // ── encode/parse 往返 ──
    @Test
    fun roundTrip() {
        val catalog = RemoteCatalog(
            version = 3,
            presets = listOf(
                RemotePreset("a", "A", "CLOUD", "https://a.com/v1", "m1"),
                RemotePreset("b", "B", "LOCAL", "http://127.0.0.1/v1", "m2"),
            ),
        )
        val presets = ProviderCatalog.parse(ProviderCatalog.encode(catalog))
        assertEquals(2, presets.size)
        assertEquals("m2", presets[1].model)
    }
}
