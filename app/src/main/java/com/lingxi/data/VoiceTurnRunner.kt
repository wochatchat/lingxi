package com.lingxi.data

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音回合执行器（R3 抽出，主页 VM 与悬浮胶囊服务共用）：
 * 取可用 Provider → 云 ASR → ConversationEngine 级联。
 * 返回 false = 没有可用 Provider（未做任何状态变更）。
 */
@Singleton
class VoiceTurnRunner @Inject constructor(
    private val repo: ProviderRepository,
    private val engine: ConversationEngine,
) {
    suspend fun runVoiceTurn(pcm: ByteArray, sampleRate: Int): Boolean {
        if (pcm.isEmpty()) return false
        val provider = repo.firstEnabled() ?: return false
        val (config, key) = provider
        val asr = OpenAiAsrEngine().apply {
            currentBaseUrl = config.baseUrl
            currentApiKey = key
        }
        engine.runVoiceTurn(pcm, sampleRate, asr, key, config.baseUrl, config.model)
        return true
    }
}
