package com.lingxi.service

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
}
