package com.lingxi.data

/**
 * barge-in 打断门（R4，PRD §2.2「TTS 播报可被随时打断」）。
 *
 * 播报期间麦克风仍在收音（常听管线不停采），喂入本门做打断判定：
 * 能量持续超过阈值达 [bargeInMs] 才触发——单帧尖峰（回声尾音/咳嗽/碰撞）不打断，
 * 用户真的开口说话（覆盖在播报上）才打断。
 *
 * 纯逻辑无 Android 依赖，可单测。播报经喇叭漏进麦克风的回声通常短促且低于
 * 人声持续能量，双保险（高阈值 + 持续时长）首版够用；后续换回声消除（AEC）再调。
 */
class BargeInGate(
    /** 持续说话超过该时长才打断（100ms 块粒度下 250ms ≈ 3 块触发，含块内延迟 <400ms） */
    private val bargeInMs: Int = 250,
    /** 打断判定阈值：高于常听 VAD（播报期间阈值抬高，压喇叭漏音） */
    private val thresholdRms: Double = 900.0,
) {
    private var speechMs = 0

    /**
     * 馈入一帧能量。
     * @return true = 触发打断（触发后自动复位，不会连续重复触发）
     */
    fun feed(rms: Double, chunkMs: Int): Boolean {
        return if (rms >= thresholdRms) {
            speechMs += chunkMs
            if (speechMs >= bargeInMs) {
                reset()
                true
            } else false
        } else {
            speechMs = 0
            false
        }
    }

    /** 复位（打断已执行/退出播报态时调用） */
    fun reset() {
        speechMs = 0
    }
}
