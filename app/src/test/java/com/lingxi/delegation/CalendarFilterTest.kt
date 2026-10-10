package com.lingxi.delegation

import com.lingxi.data.delegation.CalendarEvent
import com.lingxi.data.delegation.CalendarReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarFilterTest {

    private val now = 1_000_000_000_000L

    private fun event(id: Long, title: String, beginOffsetMin: Long) =
        CalendarEvent(eventId = id, title = title, begin = now + beginOffsetMin * 60_000L, end = now + (beginOffsetMin + 1) * 60_000L)

    @Test
    fun `窗口内事件按开始时间升序`() {
        val events = listOf(
            event(1, "晚的", 30),
            event(2, "早的", 5),
            event(3, "窗口外", 90),
            event(4, "已开始", -10),
        )
        val due = CalendarReader.filterUpcoming(events, now, leadMinutes = 30)
        assertEquals(listOf(2L, 1L), due.map { it.eventId })
    }

    @Test
    fun `空列表返回空`() {
        assertTrue(CalendarReader.filterUpcoming(emptyList(), now, 15).isEmpty())
    }

    @Test
    fun `display 格式含时间与地点`() {
        val e = CalendarEvent(1, "评审", begin = now + 14 * 60_000L, end = now + 15 * 60_000L, location = "会议室A")
        assertTrue(e.display().contains("会议室A"))
    }

    @Test
    fun `describe 马上与倒计时两种`() {
        val now = System.currentTimeMillis()
        val soon = CalendarEvent(1, "会", begin = now + 5 * 60_000L, end = now + 6 * 60_000L)
        val later = CalendarEvent(2, "会", begin = now + 25 * 60_000L, end = now + 26 * 60_000L)
        assertTrue(soon.describe(15).contains("马上"))
        // 不写死具体分钟数：now 捕获到 describe 执行之间会流逝毫秒，整数除法可能掉 1 分钟
        val desc = later.describe(15)
        assertTrue(desc.contains("分钟后"))
        assertFalse(soon.describe(15).contains("分钟后"))
    }
}
