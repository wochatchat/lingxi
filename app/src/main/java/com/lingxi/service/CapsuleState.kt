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
