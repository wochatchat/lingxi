package com.lingxi.data

import android.content.Context
import com.lingxi.data.OpenAiAsrEngine
import com.lingxi.data.asr.AsrPath
import com.lingxi.data.asr.SystemSpeechAsr
import com.lingxi.data.asr.planAsrPath
import com.lingxi.data.net.NetStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音回合执行器（R3 抽出，主页 VM 与悬浮胶囊服务共用）：
 * R15 起带 ASR 降级链：云 ASR → 系统 SpeechRecognizer（离线优先）→ 文本输入提示。
 * 决策依据：是否配置云 Provider / 网络三档 / 全离线开关 / 系统识别可用性。
 * 返回 false = 没有可用 ASR 通道（未做任何状态变更，调用方提示改文本输入）。
 */
@Singleton
class VoiceTurnRunner @Inject constructor(
    @ApplicationContext private val app: Context,
    private val repo: ProviderRepository,
    private val engine: ConversationEngine,
    private val net: NetStatus,
) {
    suspend fun runVoiceTurn(pcm: ByteArray, sampleRate: Int): Boolean {
        if (pcm.isEmpty()) return false
        val provider = repo.firstEnabled()
        val systemAsr = SystemSpeechAsr(app)
        val path = planAsrPath(
            hasCloud = provider != null,
            systemAvailable = systemAsr.available(),
            net = net.current(),
            offlineMode = engine.offlineMode,
        )
        return when (path) {
            AsrPath.CLOUD -> {
                val (config, key) = provider ?: return false
                val cloudText = runCatching {
                    cloudAsr(config.baseUrl, key).transcribe(pcm, sampleRate).getOrNull()
                }.getOrNull()
                if (!cloudText.isNullOrBlank()) {
                    engine.runVoiceTurn(pcm, sampleRate, cloudAsr(config.baseUrl, key), key, config.baseUrl, config.model)
                    true
                } else {
                    // 云端识别失败/超时 → 系统识别回落（系统识别实时收音，不吃已录 PCM）
                    trySystemTurn(systemAsr)
                }
            }
            AsrPath.SYSTEM -> trySystemTurn(systemAsr)
            AsrPath.NONE -> false
        }
    }

    /** 系统识别兜底：实时收音一次 → 识别文本进对话轮（未配置 Provider 时 key 传空，引擎有友好兜底） */
    private suspend fun trySystemTurn(systemAsr: SystemSpeechAsr): Boolean {
        if (!systemAsr.available()) return false
        val text = runCatching { systemAsr.recognizeOnce() }.getOrNull()?.getOrNull()
        val (config, key) = repo.firstEnabled() ?: return false
        engine.runRecognizedText(text.orEmpty(), key, config.baseUrl, config.model)
        return true
    }

    private fun cloudAsr(baseUrl: String, key: String): OpenAiAsrEngine =
        OpenAiAsrEngine().apply {
            currentBaseUrl = baseUrl
            currentApiKey = key
        }
}
