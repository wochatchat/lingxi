package com.lingxi

import com.lingxi.data.BargeInGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BargeInGateTest {

    private fun gate() = BargeInGate(bargeInMs = 250, thresholdRms = 900.0)

    @Test
    fun `静音帧不触发`() {
        val g = gate()
        repeat(10) { assertFalse(g.feed(100.0, 100)) }
    }

    @Test
    fun `单帧尖峰不打断`() {
        val g = gate()
        assertFalse(g.feed(1500.0, 100))
        assertFalse(g.feed(100.0, 100))
        assertFalse(g.feed(100.0, 100))
    }

    @Test
    fun `持续说话达到时长触发打断`() {
        val g = gate()
        assertFalse(g.feed(1200.0, 100)) // 100ms
        assertFalse(g.feed(1200.0, 100)) // 200ms
        assertTrue(g.feed(1200.0, 100))  // 300ms ≥ 250ms
    }

    @Test
    fun `触发后自动复位 不连续重复触发`() {
        val g = gate()
        repeat(2) { g.feed(1200.0, 100) }
        assertTrue(g.feed(1200.0, 100))
        assertFalse(g.feed(1200.0, 100)) // 重新累计
    }

    @Test
    fun `中间出现静音帧会清零累计`() {
        val g = gate()
        repeat(2) { g.feed(1200.0, 100) }
        assertFalse(g.feed(100.0, 100)) // 断开重新计
        assertFalse(g.feed(1200.0, 100))
        assertFalse(g.feed(1200.0, 100))
        assertTrue(g.feed(1200.0, 100))
    }

    @Test
    fun `reset 清零累计`() {
        val g = gate()
        repeat(2) { g.feed(1200.0, 100) }
        g.reset()
        assertFalse(g.feed(1200.0, 100))
    }

    @Test
    fun `低于阈值但接近阈值不打断`() {
        val g = gate()
        repeat(5) { assertFalse(g.feed(899.9, 100)) }
    }
}
