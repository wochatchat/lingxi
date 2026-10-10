package com.lingxi

import com.lingxi.data.delegation.TaskTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class TaskTimeTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    /** 固定「现在」：2026-10-10 10:00 周六 */
    private val now: Long = LocalDateTime.of(2026, 10, 10, 10, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `冒号时刻今天已过则解析为明天`() {
        val w = TaskTime.parse("8:30", now, zone) as TaskTime.When.At
        val expected = LocalDateTime.of(2026, 10, 11, 8, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, w.epochMillis)
    }

    @Test
    fun `点分时刻未过解析为今天`() {
        val w = TaskTime.parse("15点", now, zone) as TaskTime.When.At
        val expected = LocalDateTime.of(2026, 10, 10, 15, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, w.epochMillis)
        assertTrue(w.human.contains("今天"))
    }

    @Test
    fun `下午三点归一化为15点`() {
        val w = TaskTime.parse("下午3点", now, zone) as TaskTime.When.At
        val expected = LocalDateTime.of(2026, 10, 10, 15, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, w.epochMillis)
    }

    @Test
    fun `明天九点半`() {
        val w = TaskTime.parse("明天 9点半", now, zone) as TaskTime.When.At
        val expected = LocalDateTime.of(2026, 10, 11, 9, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals(expected, w.epochMillis)
    }

    @Test
    fun `两小时后相对时刻`() {
        val w = TaskTime.parse("2小时后", now, zone) as TaskTime.When.At
        assertEquals(now + 2 * 60 * 60_000L, w.epochMillis)
    }

    @Test
    fun `每天下午三点每日循环`() {
        val w = TaskTime.parse("每天下午3点", now, zone) as TaskTime.When.Daily
        assertEquals(15, w.hour)
        assertEquals(0, w.minute)
    }

    @Test
    fun `每小时间隔`() {
        val w = TaskTime.parse("每小时查一次", now, zone) as TaskTime.When.Every
        assertEquals(60, w.intervalMinutes)
    }

    @Test
    fun `每三十分钟间隔`() {
        val w = TaskTime.parse("每30分钟", now, zone) as TaskTime.When.Every
        assertEquals(30, w.intervalMinutes)
    }

    @Test
    fun `解析失败返回null`() {
        assertEquals(null, TaskTime.parse("盯着快递", now, zone))
        assertEquals(null, TaskTime.parse("", now, zone))
    }

    @Test
    fun `formatAt 带今天明天前缀`() {
        val tomorrow = now + 24 * 60 * 60_000L
        assertTrue(TaskTime.formatAt(now, zone).startsWith("今天"))
        assertTrue(TaskTime.formatAt(tomorrow, zone).startsWith("明天"))
    }
}
