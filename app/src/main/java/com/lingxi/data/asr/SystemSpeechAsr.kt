package com.lingxi.data.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 系统 SpeechRecognizer 识别（R15 离线 ASR 兜底）：
 * - 需要空闲麦克风（自身采集音频，不能喂 PCM）——PTT 抬手后 / 云端失败降级时调用；
 * - EXTRA_PREFER_OFFLINE 优先端侧模型（ROM 碎片化：部分机型无离线包，降级失败即提示改文本输入）；
 * - recognizeOnce 一次性识别，超时/失败/无结果统一收敛为 Result.failure。
 */
class SystemSpeechAsr(private val context: android.content.Context) {

    /** 系统识别是否可用（RecognizerIntent 是否有接收方 + 服务存在） */
    fun available(): Boolean = runCatching {
        SpeechRecognizer.isRecognitionAvailable(context)
    }.getOrDefault(false)

    /** 单次识别：实时收音直到用户停顿。限定时长防永久挂起。 */
    suspend fun recognizeOnce(timeoutMs: Long = 15_000L): Result<String> {
        val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrElse {
            return Result.failure(it)
        }
        if (recognizer == null) return Result.failure(IllegalStateException("SpeechRecognizer 不可用"))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        return try {
            withTimeoutOrNull(timeoutMs) { awaitResult(recognizer, intent) }
                ?: Result.failure(java.util.concurrent.TimeoutException("系统识别超时"))
        } finally {
            runCatching { recognizer.destroy() }
        }
    }

    private suspend fun awaitResult(
        recognizer: SpeechRecognizer,
        intent: Intent,
    ): Result<String> = suspendCancellableCoroutine { cont ->
        val done = AtomicBoolean(false)
        val listener = object : RecognitionListener {
            override fun onResults(results: Bundle) {
                if (!done.compareAndSet(false, true)) return
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isBlank()) {
                    cont.resume(Result.failure(IllegalStateException("未能识别语音")))
                } else {
                    cont.resume(Result.success(text))
                }
            }

            override fun onError(error: Int) {
                if (!done.compareAndSet(false, true)) return
                cont.resume(Result.failure(IllegalStateException("系统识别错误 code=$error")))
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        cont.invokeOnCancellation {
            runCatching { recognizer.destroy() }
        }
        recognizer.setRecognitionListener(listener)
        recognizer.startListening(intent)
    }
}
