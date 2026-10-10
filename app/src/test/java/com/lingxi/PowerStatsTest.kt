package com.lingxi

import com.lingxi.data.power.BatterySample
import com.lingxi.data.power.PowerStatsCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PowerStatsTest {

    private val now = 1_700_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun `空样本给提示`() {
        val s = PowerStatsCalculator.compute(emptyList(), now)
        assertEquals(null, s.currentLevel)
        assertEquals(null, s.drop24h)
    }

    @Test
    fun `满 24h 非充电样本算出掉电`() {
        // 25h 前 80% → 现在 60% = 掉 20 个百分点
        val samples = listOf(
            BatterySample(now - day - 3_600_000, 80, false),
            BatterySample(now, 60, false),
        )
        val s = PowerStatsCalculator.compute(samples, now)
        assertEquals(60, s.currentLevel)
        assertEquals(20, s.drop24h)
    }

    @Test
    fun `充电时段干扰则不下结论`() {
        val samples = listOf(
            BatterySample(now - day, 80, charging = true),
            BatterySample(now, 60, charging = false),
        )
        val s = PowerStatsCalculator.compute(samples, now)
        assertEquals(null, s.drop24h)
    }

    @Test
    fun `采样不足6小时不给掉电值`() {
        val samples = listOf(
            BatterySample(now - 2 * 60 * 60 * 1000L, 80, false),
            BatterySample(now, 70, false),
        )
        val s = PowerStatsCalculator.compute(samples, now)
        assertEquals(70, s.currentLevel)
        assertEquals(null, s.drop24h)
    }

    @Test
    fun `单样本只有当前电量`() {
        val s = PowerStatsCalculator.compute(listOf(BatterySample(now, 55, false)), now)
        assertEquals(55, s.currentLevel)
        assertEquals(null, s.drop24h)
    }

    @Test
    fun `基准取最接近24h前的样本`() {
        val samples = listOf(
            BatterySample(now - day - 1, 90, false),
            BatterySample(now - day + 2 * 60 * 60 * 1000L, 78, false), // 离 24h 前最近
            BatterySample(now, 65, false),
        )
        val s = PowerStatsCalculator.compute(samples, now)
        assertEquals(25, s.drop24h)
    }
}
