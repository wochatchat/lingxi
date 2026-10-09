package com.lingxi.data

/**
 * 能量 VAD（R3 常听管线的 G1 门控）。
 *
 * G0（服务级）：常听开关 + 麦克风权限 + 有可用 Provider，由 FloatingCapsuleService 判定。
 * G1（帧级）：本类按 RMS 能量判定说话起止；后续接 sherpa-onnx silero VAD 时
 * 实现 [VadDetector] 接口替换，上层管线不动。
 */
sealed interface VadEvent {
    /** 检测到说话开始（从静音转入） */
    data object SpeechStart : VadEvent

    /** 静音超过挂起时长，说话结束 */
    data object SpeechEnd : VadEvent
}

data class VadConfig(
    /** RMS 阈值：高于它视为语音帧 */
    val thresholdRms: Double = 500.0,
    /** 静音挂起：连续静音超过该时长判定说话结束 */
    val hangoverSilenceMs: Int = 700,
)

/** 帧馈入式能量 VAD 状态机（纯逻辑，可单测）。 */
class EnergyVad(private val cfg: VadConfig = VadConfig()) {

    private var inSpeech = false
    private var silenceMs = 0

    /**
     * 馈入一帧。
     * @param rms 本帧 RMS 能量
     * @param chunkMs 本帧时长（毫秒）
     * @return 状态跃迁事件；无跃迁返回 null
     */
    fun feed(rms: Double, chunkMs: Int): VadEvent? {
        if (rms >= cfg.thresholdRms) {
            silenceMs = 0
            if (!inSpeech) {
                inSpeech = true
                return VadEvent.SpeechStart
            }
        } else if (inSpeech) {
            silenceMs += chunkMs
            if (silenceMs >= cfg.hangoverSilenceMs) {
                inSpeech = false
                silenceMs = 0
                return VadEvent.SpeechEnd
            }
        }
        return null
    }

    /** 复位到静音态（打断/停播后重置挂起计时） */
    fun reset() {
        inSpeech = false
        silenceMs = 0
    }
}

/** 语音段有效性（过短 = 噪声/咳嗽，丢弃不打扰） */
fun segmentValid(durationMs: Int, minSpeechMs: Int = 300): Boolean = durationMs >= minSpeechMs
