package com.lingxi

import com.lingxi.data.memory.BehaviorPredictor
import com.lingxi.data.memory.BehaviorRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class BehaviorPredictorTest {

    // 固定参考时刻：2026-10-11（周日）14:30 本地时区
    private fun at(year: Int, month1: Int, day: Int, hour: Int, minute: Int = 0): Long {
        val cal = Calendar.getInstance()
        cal.set(year, month1 - 1, day, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private val now = at(2026, 10, 11, 14, 30) // 周日 14:30 → 桶 7（14/2）

    // ---- 分桶 ----

    @Test
    fun `时段分桶 - 每 2 小时一桶`() {
        assertEquals(0, BehaviorPredictor.hourBucket(at(2026, 10, 11, 0, 30)))
        assertEquals(0, BehaviorPredictor.hourBucket(at(2026, 10, 11, 1, 59)))
        assertEquals(7, BehaviorPredictor.hourBucket(now))
        assertEquals(11, BehaviorPredictor.hourBucket(at(2026, 10, 11, 23, 0)))
    }

    @Test
    fun `星期映射 - 周一为 1 周日为 7`() {
        // 2026-10-11 是周日
        assertEquals(7, BehaviorPredictor.weekday(now))
        // 2026-10-05 是周一
        assertEquals(1, BehaviorPredictor.weekday(at(2026, 10, 12, 8, 0)))
    }

    // ---- 建议打分 ----

    private fun row(
        bucket: Int,
        weekday: Int,
        action: String,
        count: Int,
        ignored: Int = 0,
    ) = BehaviorRow(bucket, weekday, action, count, ignored)

    @Test
    fun `无数据不产生建议`() {
        assertTrue(BehaviorPredictor.suggest(emptyList(), now).isEmpty())
    }

    @Test
    fun `低于最小观测次数不产生建议`() {
        val suggestions = BehaviorPredictor.suggest(listOf(row(7, 7, "alarm", BehaviorPredictor.MIN_OBSERVATIONS - 1)), now)
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `命中习惯时段给出高置信建议`() {
        val rows = listOf(row(7, 7, "alarm", count = 5), row(7, 7, "chat", count = 4))
        val suggestions = BehaviorPredictor.suggest(rows, now)
        assertEquals(2, suggestions.size)
        assertEquals("alarm", suggestions[0].action) // count 高者排前
        assertEquals("定个闹钟", suggestions[0].label)
        assertEquals("帮我定一个闹钟", suggestions[0].query)
        // 4/5 = 0.8 ≥ 0.6 → 第二条也在阈值内
        assertTrue(suggestions[1].confidence >= BehaviorPredictor.MIN_CONFIDENCE)
    }

    @Test
    fun `同桶跨星期权重减半`() {
        // alarm：周日 5 次(×1.0) + 周一 5 次(×0.5) = 7.5；search 只有周日 5 次
        val rows = listOf(
            row(7, 7, "alarm", 5),
            row(7, 1, "alarm", 5),
            row(7, 7, "search", 5),
        )
        val suggestions = BehaviorPredictor.suggest(rows, now)
        assertEquals("alarm", suggestions[0].action)
        assertEquals(1.0, suggestions.first { it.action == "alarm" }.confidence, 0.001)
        assertTrue(suggestions.first { it.action == "search" }.confidence < 1.0)
    }

    @Test
    fun `被忽略多的建议自然沉底`() {
        // 有效计数 = count - 1.5 × ignored，6 - 1.5×5 ≤ 0 → 不推荐
        val ignored = BehaviorPredictor.suggest(listOf(row(7, 7, "alarm", 6, ignored = 5)), now)
        assertTrue(ignored.isEmpty())
    }

    @Test
    fun `置信度低于阈值的行为不出现在建议里`() {
        val rows = listOf(row(7, 7, "alarm", 8), row(7, 7, "chat", 3))
        val suggestions = BehaviorPredictor.suggest(rows, now)
        assertTrue(suggestions.all { it.confidence >= BehaviorPredictor.MIN_CONFIDENCE })
        // 4/9 < 0.6 → chat 被过滤
        assertTrue(suggestions.none { it.action == "chat" })
    }

    // ---- 工具名映射与建议目录 ----

    @Test
    fun `DIRECT 工具名映射行为 id`() {
        assertEquals("alarm", BehaviorPredictor.actionForTool("set_alarm"))
        assertEquals("app", BehaviorPredictor.actionForTool("open_app"))
        assertEquals("search", BehaviorPredictor.actionForTool("web_search"))
        assertEquals("delegation", BehaviorPredictor.actionForTool("manage_task"))
        assertEquals("chat", BehaviorPredictor.actionForTool("unknown_tool"))
    }

    @Test
    fun `建议目录 - 有目录的行为生成 chips 无目录不生成`() {
        assertTrue(BehaviorPredictor.catalog("alarm") != null)
        assertTrue(BehaviorPredictor.catalog("morning_report") != null)
        // open_app 无目标名，不生成 chip
        assertTrue(BehaviorPredictor.catalog("app") == null)
    }
}
