package com.lingxi.delegation

import com.lingxi.data.delegation.MorningReportComposer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MorningReportComposerTest {

    private val composer = MorningReportComposer()

    @Test
    fun `完整晨报含三段`() {
        val text = composer.compose(
            MorningReportComposer.Inputs(
                dateLine = "今天 08:00",
                weather = "晴 18°C",
                events = listOf("10:00 周会", "14:00 评审"),
                activeTasks = listOf("盯快递", "每天打坐"),
            ),
        )
        assertTrue(text.contains("天气：晴 18°C"))
        assertTrue(text.contains("2件日程"))
        assertTrue(text.contains("2个委托"))
        assertTrue(text.contains("10:00 周会"))
    }

    @Test
    fun `无天气无日程降级`() {
        val text = composer.compose(
            MorningReportComposer.Inputs(
                dateLine = "今天 08:00",
                weather = null,
                events = emptyList(),
                activeTasks = emptyList(),
            ),
        )
        assertTrue(text.contains("没有日程"))
        assertTrue(text.contains("没有进行中的委托"))
        assertFalse(text.contains("天气："))
    }

    @Test
    fun `有日程无委托`() {
        val text = composer.compose(
            MorningReportComposer.Inputs(
                dateLine = "今天 08:00",
                weather = "多云 20°C",
                events = listOf("10:00 站会"),
                activeTasks = emptyList(),
            ),
        )
        assertTrue(text.contains("1件日程"))
        assertTrue(text.contains("没有进行中的委托"))
    }
}
