package com.lingxi.service

import com.lingxi.data.ConvState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleMathTest {

    @Test
    fun `clamp 越界收敛到边界`() {
        assertEquals(5f, CapsuleMath.clamp(1f, 5f, 100f))
        assertEquals(50f, CapsuleMath.clamp(50f, 5f, 50f))
        assertEquals(5f, CapsuleMath.clamp(-3f, 5f, 50f))
        assertEquals(100f, CapsuleMath.clamp(200f, 5f, 100f))
    }

    @Test
    fun `snapTargetX 左半屏吸左缘 右半屏吸右缘`() {
        val screenW = 1080
        val size = 132
        assertEquals(0f, CapsuleMath.snapTargetX(100f, screenW, size))
        assertEquals((screenW - size).toFloat(), CapsuleMath.snapTargetX(900f, screenW, size))
        // 恰在中心 → 左缘
        assertEquals(0f, CapsuleMath.snapTargetX((screenW - size) / 2f, screenW, size))
    }

    @Test
    fun `exceededSlop 阈值判定`() {
        assertTrue(CapsuleMath.exceededSlop(11f, 0f, 8f))
        assertTrue(CapsuleMath.exceededSlop(0f, -9f, 8f))
        // 逐轴判定：对角 7,7 单轴未超 slop，不判拖动
        assertFalse(CapsuleMath.exceededSlop(7f, 7f, 8f))
        assertFalse(CapsuleMath.exceededSlop(0f, 0f, 8f))
    }

    // ---------- R14 胶囊状态形变纯函数 ----------

    @Test
    fun `capsuleColorLerp 端点与中点`() {
        val from = 0xCC1565C0.toInt()
        val to = 0xCCEF6C00.toInt()
        assertEquals(from, capsuleColorLerp(from, to, 0f))
        assertEquals(to, capsuleColorLerp(from, to, 1f))
        // 越界收敛
        assertEquals(to, capsuleColorLerp(from, to, 2f))
        assertEquals(from, capsuleColorLerp(from, to, -1f))
        // 中点：各通道取中值（忽略舍入 ±1）
        fun ch(c: Int, s: Int) = (c ushr s) and 0xFF
        val mid = capsuleColorLerp(from, to, 0.5f)
        assertTrue(Math.abs(ch(mid, 24) - ch(from, 24)) <= 1)
        assertTrue(Math.abs(ch(mid, 8) - (ch(from, 8) + ch(to, 8)) / 2) <= 1)
    }

    @Test
    fun `capsuleScaleFor 只有播报态呼吸`() {
        assertEquals(1f, capsuleScaleFor(ConvState.Idle, 0.25f))
        assertEquals(1f, capsuleScaleFor(ConvState.Failed("", "e"), 0.5f))
        // Speaking：相位 0 与 1 回到 1.0，相位 0.5 处最大 1.05
        assertEquals(1.0f, capsuleScaleFor(ConvState.Speaking(""), 0f), 1e-4f)
        assertEquals(1.0f, capsuleScaleFor(ConvState.Speaking(""), 1f), 1e-4f)
        assertEquals(1.05f, capsuleScaleFor(ConvState.Speaking(""), 0.5f), 1e-4f)
        // 呼吸不过冲
        assertTrue(capsuleScaleFor(ConvState.Speaking(""), 0.3f) in 0.99f..1.06f)
    }
}
