package com.lingxi

import com.lingxi.data.UiAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiActionTest {

    // ---- 语音等价通道：ChoiceSheet ----

    @Test
    fun `语音选择 - 第N个`() {
        assertEquals(0, UiAction.parseVoiceChoice("第1个"))
        assertEquals(1, UiAction.parseVoiceChoice("第 2 个"))
        assertEquals(2, UiAction.parseVoiceChoice("第三个"))
        assertEquals(9, UiAction.parseVoiceChoice("第十个"))
    }

    @Test
    fun `语音选择 - 裸数字与中文数字`() {
        assertEquals(1, UiAction.parseVoiceChoice("2"))
        assertEquals(2, UiAction.parseVoiceChoice("三"))
    }

    @Test
    fun `非应答文本返回 null`() {
        assertNull(UiAction.parseVoiceChoice("不是这个意思"))
        assertNull(UiAction.parseVoiceChoice("帮我查天气"))
        assertNull(UiAction.parseVoiceChoice(""))
    }

    // ---- ConfirmGate 语音解析 ----

    @Test
    fun `确认与取消词命中`() {
        assertEquals(true, UiAction.parseConfirm("确认"))
        assertEquals(true, UiAction.parseConfirm("可以"))
        assertEquals(false, UiAction.parseConfirm("算了"))
        assertEquals(false, UiAction.parseConfirm("不要"))
        assertNull(UiAction.parseConfirm("再说一遍"))
    }

    // ---- 语音提示生成 ----

    @Test
    fun `ChoiceSheet 语音提示带编号`() {
        val a = UiAction(type = UiAction.UiType.ChoiceSheet, title = "t", options = listOf("A", "B"))
        val p = UiAction.voicePrompt(a)
        assertTrue(p.contains("1. A"))
        assertTrue(p.contains("2. B"))
    }

    @Test
    fun `ConfirmGate 语音提示带确认取消词`() {
        val a = UiAction(type = UiAction.UiType.ConfirmGate, title = "发短信", body = "给妈妈：我到了")
        val p = UiAction.confirmVoicePrompt(a)
        assertTrue(p.contains("确认"))
        assertTrue(p.contains("取消"))
        assertTrue(p.contains("我到了"))
    }
}

// ---- R9 Stream-UI 七原语辅助 ----

class UiActionParamsTest {

    private fun panel() = UiAction(
        type = UiAction.UiType.ParamPanel,
        title = "盯快递",
        fields = listOf(
            UiAction.ParamField("interval_minutes", "多久查一次", "60", "分钟"),
            UiAction.ParamField("notify_mode", "怎么通知", "通知栏"),
        ),
    )

    @Test
    fun `参数默认 JSON`() {
        assertEquals(
            """{"interval_minutes":"60","notify_mode":"通知栏"}""",
            UiAction.paramDefaultJson(panel()),
        )
    }

    @Test
    fun `参数摘要 - 全默认`() {
        assertEquals("多久查一次：60；怎么通知：通知栏", UiAction.paramSummary(panel(), emptyMap()))
    }

    @Test
    fun `参数摘要 - 部分改值`() {
        assertEquals(
            "多久查一次：30；怎么通知：通知栏",
            UiAction.paramSummary(panel(), mapOf("interval_minutes" to "30")),
        )
    }

    @Test
    fun `confirmVoicePrompt 覆盖 TakeoverPrompt`() {
        val t = UiAction(type = UiAction.UiType.TakeoverPrompt, title = "代回", body = "这条我来回可以吗", ttlMs = 10_000)
        assertTrue(UiAction.confirmVoicePrompt(t).contains("可以吗"))
    }

    @Test
    fun `迷你图表与进度原语可构造`() {
        val bar = UiAction(type = UiAction.UiType.ProgressBar, title = "下载", progress = 42)
        val chart = UiAction(type = UiAction.UiType.MiniChart, title = "趋势", values = listOf(1f, 3f, 2f))
        assertEquals(42, bar.progress)
        assertEquals(3, chart.values.size)
    }
}
