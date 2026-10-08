package com.lingxi

import com.lingxi.data.ProviderCodec
import com.lingxi.data.ProviderConfig
import com.lingxi.data.ProviderKind
import com.lingxi.data.chatCompletionsUrl
import com.lingxi.data.maskKey
import com.lingxi.data.newProviderId
import com.lingxi.data.normalizeBaseUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTypesTest {

    // ── normalizeBaseUrl ──
    @Test
    fun normalizeTrimsAndStripsTrailingSlash() {
        assertEquals("https://api.x.com/v1", normalizeBaseUrl("  https://api.x.com/v1/  "))
        assertEquals("https://api.x.com/v1", normalizeBaseUrl("https://api.x.com/v1///"))
    }

    @Test
    fun normalizeKeepsVersionSegments() {
        // 不同网关路径各异，不自动补 /v1
        assertEquals("https://open.bigmodel.cn/api/paas/v4", normalizeBaseUrl("https://open.bigmodel.cn/api/paas/v4/"))
        assertEquals("https://ark.cn-beijing.volces.com/api/v3", normalizeBaseUrl("https://ark.cn-beijing.volces.com/api/v3"))
    }

    @Test
    fun chatCompletionsUrlAppendsEndpoint() {
        assertEquals("https://api.x.com/v1/chat/completions", chatCompletionsUrl("https://api.x.com/v1"))
        assertEquals("https://api.x.com/v1/chat/completions", chatCompletionsUrl("https://api.x.com/v1/"))
        // 用户误把端点粘进 baseUrl 也容错
        assertEquals("https://api.x.com/v1/chat/completions", chatCompletionsUrl("https://api.x.com/v1/chat/completions"))
    }

    // ── maskKey ──
    @Test
    fun maskKeyShortAndLong() {
        assertEquals("****", maskKey(""))
        assertEquals("****", maskKey("12345678"))
        assertEquals("sk-1…wxyz", maskKey("sk-1234567890wxyz"))
    }

    // ── Codec 往返 ──
    @Test
    fun codecRoundTrip() {
        val list = listOf(
            ProviderConfig("p1", "DeepSeek", kind = com.lingxi.data.ProviderKind.CLOUD, baseUrl = "https://api.deepseek.com/v1", model = "deepseek-chat"),
            ProviderConfig("p2", "中转", kind = ProviderKind.RELAY, "https://gw.io/v1", "gpt-4o-mini", enabled = false),        )
        val decoded = ProviderCodec.decode(ProviderCodec.encode(list))
        assertEquals(list, decoded)
    }

    @Test
    fun decodeGarbageReturnsEmpty() {
        assertEquals(emptyList<ProviderConfig>(), ProviderCodec.decode("not json"))
        assertEquals(emptyList<ProviderConfig>(), ProviderCodec.decode(""))
    }

    // ── 预设 ──
    @Test
    fun presetBaseUrlsAreNormalized() {
        // CUSTOM_PRESET 的 "https://" 占位会被规整成 "https:"，属预期（用户必填真实地址），不参与校验
        com.lingxi.data.PROVIDER_PRESETS.forEach { p ->
            assertEquals(p.baseUrl, normalizeBaseUrl(p.baseUrl))
        }
    }

    @Test
    fun newIdsAreUnique() {
        val ids = (1..50).map { newProviderId() }.toSet()
        assertEquals(50, ids.size)
    }
}
