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

    // ---- R9 F13：快递参数 ----

    @Test
    fun `poll 显式 tracking_no 走快递`() {
        val r = parse(
            "action" to "create", "kind" to "poll",
            "title" to "盯快递", "interval_minutes" to "60",
            "tracking_no" to "SF1234567890", "company" to "shunfeng",
        )
        val cmd = (r as DelegationArgs.Result.Ok).command as DelegationArgs.Command.CreatePoll
        assertEquals("SF1234567890", cmd.trackingNo)
        assertEquals("shunfeng", cmd.company)
    }

    @Test
    fun `poll 标题带单号自动识别快递`() {
        val r = parse("action" to "create", "title" to "盯着顺丰快递 1234567890123 到了告诉我", "when_text" to "每小时")
        val cmd = (r as DelegationArgs.Result.Ok).command as DelegationArgs.Command.CreatePoll
        assertEquals("1234567890123", cmd.trackingNo)
        assertEquals("shunfeng", cmd.company)
    }

    @Test
    fun `普通 poll 无单号`() {
        val r = parse("action" to "create", "title" to "盯群里消息", "interval_minutes" to "30")
        val cmd = (r as DelegationArgs.Result.Ok).command as DelegationArgs.Command.CreatePoll
        assertTrue(cmd.trackingNo.isBlank())
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

    // ---- R10 F13：航班参数 ----

    @Test
    fun `poll 显式 flight_no 走航班`() {
        val r = parse(
            "action" to "create", "kind" to "poll",
            "title" to "盯航班", "interval_minutes" to "60",
            "flight_no" to "CA1234",
        )
        val cmd = (r as DelegationArgs.Result.Ok).command as DelegationArgs.Command.CreatePoll
        assertEquals("CA1234", cmd.flightNo)
    }

    @Test
    fun `标题里的航班号自动提取`() {
        val r = parse("action" to "create", "kind" to "poll", "title" to "盯 MU5678 落地", "interval_minutes" to "30")
        val cmd = (r as DelegationArgs.Result.Ok).command as DelegationArgs.Command.CreatePoll
        assertEquals("MU5678", cmd.flightNo)
    }

    @Test
    fun `快递单号优先于航班号`() {
        val r = parse(
            "action" to "create", "kind" to "poll",
            "title" to "盯 SF1234567890", "interval_minutes" to "60",
        )
        val cmd = (r as DelegationArgs.Result.Ok).command as DelegationArgs.Command.CreatePoll
        assertEquals("SF1234567890", cmd.trackingNo)
        assertTrue(cmd.flightNo.isBlank())
    }

    // ---- R10：update_interval（ParamPanel 直连回写） ----

    @Test
    fun `update_interval 按 id`() {
        val r = parse("action" to "update_interval", "id" to "3", "interval_minutes" to "45")
        val cmd = (r as DelegationArgs.Result.Ok).command as DelegationArgs.Command.UpdateInterval
        assertEquals(3L, cmd.id)
        assertEquals(45, cmd.intervalMinutes)
    }

    @Test
    fun `update_interval 按 title`() {
        val r = parse("action" to "update_interval", "title" to "盯快递", "interval_minutes" to "120")
        val cmd = (r as DelegationArgs.Result.Ok).command as DelegationArgs.Command.UpdateInterval
        assertEquals("盯快递", cmd.title)
        assertEquals(120, cmd.intervalMinutes)
    }

    @Test
    fun `update_interval 缺间隔报错`() {
        assertTrue(parse("action" to "update_interval", "id" to "3") is DelegationArgs.Result.Error)
        assertTrue(parse("action" to "update_interval", "id" to "3", "interval_minutes" to "5")
            is DelegationArgs.Result.Error)
        assertTrue(parse("action" to "update_interval", "interval_minutes" to "60")
            is DelegationArgs.Result.Error)
    }
}
