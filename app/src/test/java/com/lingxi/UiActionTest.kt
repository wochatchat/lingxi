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
