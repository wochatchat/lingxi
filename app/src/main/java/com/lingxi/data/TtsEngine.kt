package com.lingxi.data

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TTS 引擎抽象（F3）。R2：系统 TTS 本地兜底（PRD 默认本地，云端高质量音色后续轮接入）。
 * 句子级队列播报：[enqueue] 顺序排队，[awaitIdle] 挂起直到全部播完（流式预读基座）。
 */
interface TtsEngine {
    val ready: Boolean
    fun enqueue(text: String)
    fun stop()
    suspend fun awaitIdle()
}

/** 系统 TextToSpeech 引擎（离线可用，随系统音色） */
@Singleton
class SystemTtsEngine @javax.inject.Inject constructor(
    @ApplicationContext private val context: Context,
) : TtsEngine {

    private val initDone = CompletableDeferred<Boolean>()
    private val counter = AtomicLong(0)

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var initOk = false
    @Volatile private var pending = 0

    // 每次 enqueue 换新信号；队列里最后一句播完（或 stop）时 complete
    @Volatile private var idleSignal: CompletableDeferred<Unit> = CompletableDeferred()

    private fun ensureInit(): Boolean {
        if (tts != null) return initOk
        val engine = TextToSpeech(context) { status ->
            initOk = status == TextToSpeech.SUCCESS
            if (initOk) {
                runCatching { tts?.language = Locale.SIMPLIFIED_CHINESE }
            }
            initDone.complete(initOk)
        }
        tts = engine
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { settle() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { settle() }
        })
        return initOk
    }

    /** 一句播完/失败：队列清空时放行 [idleSignal] */
    private fun settle() {
        if (pending.decrementAndGet() <= 0) {
            val s = idleSignal
            if (!s.isCompleted) s.complete(Unit)
        }
    }

    override val ready: Boolean
        get() = runCatching { ensureInit() }.getOrDefault(false)

    override fun enqueue(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        idleSignal = CompletableDeferred()
        pending += 1
        tts?.speak(t, TextToSpeech.QUEUE_ADD, null, "lx_${counter.incrementAndGet()}")
    }

    override fun stop() {
        runCatching { tts?.stop() }
        pending = 0
        idleSignal.complete(Unit)
    }

    override suspend fun awaitIdle() {
        if (runCatching { initDone.await() }.getOrDefault(false) && pending > 0) {
            idleSignal.await()
        }
    }
}
