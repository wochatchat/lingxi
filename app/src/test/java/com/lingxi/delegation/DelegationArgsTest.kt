package com.lingxi.delegation

import com.lingxi.data.delegation.DelegationArgs
import com.lingxi.data.delegation.TaskTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class DelegationArgsTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now: Long = LocalDateTime.of(2026, 10, 10, 10, 0).atZone(zone).toInstant().toEpochMilli()

    private fun parse(vararg pairs: Pair<String, String>): DelegationArgs.Result =
        DelegationArgs.parse(mapOf(*pairs), now, zone)

    @Test
    fun `create reminder 带 when_text`() {
        val r = parse("action" to "create", "kind" to "reminder", "title" to "取快递", "when_text" to "明天 8:30")
        val cmd = (r as DelegationArgs.Result.Ok).command as DelegationArgs.Command.CreateReminder
        assertEquals("取快递", cmd.title)
        assertTrue(cmd.atMillis > now)
        assertTrue(cmd.timeHuman.isNotBlank())
    }

    @Test
    fun `每天提醒自动识别为 poll`() {
        val r = parse("action" to "create", "title" to "打坐", "when_text" to "每天下午3点")
        val cmd = (r as DelegationArgs.Result.Ok).command as DelegationArgs.Command.CreatePoll
        assertEquals(24 * 60, cmd.intervalMinutes)
    }

    @Test
    fun `poll 用 interval_minutes`() {
        val r = parse("action" to "create", "kind" to "poll", "title" to "盯快递", "interval_minutes" to "60")
        val cmd = (r as DelegationArgs.Result.Ok).command as DelegationArgs.Command.CreatePoll
        assertEquals(60, cmd.intervalMinutes)
    }

    @Test
    fun `间隔小于15分钟被抬到15`() {
        val r = parse("action" to "create", "kind" to "poll", "title" to "盯秒杀", "interval_minutes" to "5")
        val cmd = (r as DelegationArgs.Result.Ok).command as DelegationArgs.Command.CreatePoll
        assertEquals(15, cmd.intervalMinutes)
    }

    @Test
    fun `reminder 缺时间报错`() {
        val r = parse("action" to "create", "kind" to "reminder", "title" to "取快递")
        assertTrue(r is DelegationArgs.Result.Error)
    }

    @Test
    fun `create 缺标题报错`() {
        val r = parse("action" to "create", "when_text" to "8:30")
        assertTrue(r is DelegationArgs.Result.Error)
    }

    @Test
    fun `list 命令`() {
        val r = parse("action" to "list")
        assertTrue((r as DelegationArgs.Result.Ok).command is DelegationArgs.Command.List)
    }

    @Test
    fun `cancel 按 title`() {
        val r = parse("action" to "cancel", "title" to "盯快递")
        val cmd = (r as DelegationArgs.Result.Ok).command as DelegationArgs.Command.Cancel
        assertEquals("盯快递", cmd.title)
    }

    @Test
    fun `cancel 无 id 无 title 报错`() {
        val r = parse("action" to "cancel")
        assertTrue(r is DelegationArgs.Result.Error)
    }

    @Test
    fun `未知 action 报错`() {
        val r = parse("action" to "explode", "title" to "x")
        assertTrue(r is DelegationArgs.Result.Error)
    }
}
