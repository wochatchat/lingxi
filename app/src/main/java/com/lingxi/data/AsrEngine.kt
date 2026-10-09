package com.lingxi.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ASR 引擎抽象（F2）。R2 第一版契约：「一段 PCM → 文本」，
 * 云端 WS 真流式 ASR / 端侧 paraformer 在后续轮以新实现接入，接口不变。
 */
interface AsrEngine {
    suspend fun transcribe(pcm16: ByteArray, sampleRate: Int): Result<String>
}

/** OpenAI-compatible /audio/transcriptions（whisper 风格，主流云厂商与中转站都支持） */
class OpenAiAsrEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val model: String = "whisper-1",
    private val language: String = "zh",
) : AsrEngine {

    override suspend fun transcribe(pcm16: ByteArray, sampleRate: Int): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (pcm16.isEmpty()) error("未采集到语音")
                val wav = PcmWav.encode(pcm16, sampleRate)
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "speech.wav",
                        wav.toRequestBody("audio/wav".toMediaType()))
                    .addFormDataPart("model", model)
                    .addFormDataPart("language", language)
                    .build()
                val request = Request.Builder()
                    .url(transcriptionsUrl(currentBaseUrl))
                    .header("Authorization", "Bearer $currentApiKey")
                    .post(body)
                    .build()
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("ASR HTTP ${resp.code}: ${text.take(200)}")
                    JSONObject(text).optString("text").orEmptyIfBlank()
                        ?: error("ASR 响应缺少 text 字段")
                }
            }
        }

    // 端点配置按 turn 传入太散，改为调用前 set；引擎无状态共享安全（单 turn 串行）
    @Volatile var currentBaseUrl: String = ""
    @Volatile var currentApiKey: String = ""

    private fun String.orEmptyIfBlank(): String? = if (isBlank()) null else this
}

/** transcriptions 端点（纯函数可测） */
fun transcriptionsUrl(baseUrl: String): String =
    "${normalizeBaseUrl(baseUrl).removeSuffix("/audio/transcriptions")}/audio/transcriptions"
