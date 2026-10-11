package com.lingxi.service

import com.lingxi.data.ConvState

/** 胶囊状态色（ARGB，纯函数可单测）：随对话引擎状态变化 */
fun capsuleColorFor(state: ConvState): Int = when (state) {
    is ConvState.Idle -> 0xCC1B1B1F.toInt()
    is ConvState.Transcribing -> 0xCC2E7D32.toInt() // 听到/识别中：绿
    is ConvState.Thinking -> 0xCC1565C0.toInt()     // 思考：蓝
    is ConvState.Speaking -> 0xCCEF6C00.toInt()     // 播报：橙
    is ConvState.Failed -> 0xCCB71C1C.toInt()       // 失败：红
}

/**
 * R14 状态色过渡（纯函数可单测）：ARGB 各通道线性插值。
 * t ∈ [0,1]，越界收敛到端点。
 */
fun capsuleColorLerp(from: Int, to: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    fun mix(fc: Int, tc: Int): Int {
        val v = fc + (tc - fc) * k + 0.5f
        return v.toInt().coerceIn(0, 255)
    }
    val a = mix((from ushr 24) and 0xFF, (to ushr 24) and 0xFF)
    val r = mix((from ushr 16) and 0xFF, (to ushr 16) and 0xFF)
    val g = mix((from ushr 8) and 0xFF, (to ushr 8) and 0xFF)
    val b = mix(from and 0xFF, to and 0xFF)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/** R14 播报态呼吸脉冲（纯函数可单测）：scale = 1 ± 0.05 余弦起伏；其余状态恒 1.0 */
fun capsuleScaleFor(state: ConvState, phase: Float): Float = when (state) {
    is ConvState.Speaking ->
        1f + 0.05f * (0.5f - 0.5f * kotlin.math.cos((2.0 * Math.PI * phase)).toFloat())
    else -> 1f
}
