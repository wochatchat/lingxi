package com.lingxi.service

import kotlin.math.abs

/**
 * 胶囊几何纯函数（独立出来便于单测，与 LiveRecorder FloatingBallMath 同思路）。
 */
internal object CapsuleMath {

    /** 拖动时把坐标限制在屏幕内。 */
    fun clamp(x: Float, min: Float, max: Float): Float = when {
        x < min -> min
        x > max -> max
        else -> x
    }

    /** 松手贴边吸附：胶囊中心在屏幕左半 → 吸到左缘，否则吸到右缘。 */
    fun snapTargetX(rawX: Float, screenWidth: Int, viewWidth: Int): Float {
        val maxX = (screenWidth - viewWidth).toFloat()
        return if (rawX + viewWidth / 2f <= screenWidth / 2f) 0f else maxX
    }

    /** 触摸位移超过阈值 = 拖动（未超过 = 点击） */
    fun exceededSlop(dx: Float, dy: Float, slop: Float): Boolean =
        abs(dx) > slop || abs(dy) > slop
}
