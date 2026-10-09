package com.lingxi

import com.lingxi.data.AsrEngine
import com.lingxi.data.ChatMessage
import com.lingxi.data.ConvState
import com.lingxi.data.ConversationEngine
import com.lingxi.data.LlmEvent
import com.lingxi.data.LlmStream
import com.lingxi.data.TtsEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationEngineTest {

    /** 事件序列回放型 LLM fake */
    private class FakeLlm(private val events: List<LlmEvent>) : LlmStream {
        var lastMessages: List<ChatMessage> = emptyList()
        override fun streamChat(
            apiKey: String,
            baseUrl: String,
            model: String,
            messages: List<ChatMessage>,
            temperature: Double,
        ): Flow<LlmEvent> = flow {
            lastMessages = messages
            for (e in events) emit(e)
        }
    }

    /** 记录型 TTS：记录 enqueue 顺序，不发真声 */
    private class FakeTts : TtsEngine {
        val spoken = mutableListOf<String>()
        var stopped = false
        override val ready: Boolean get() = true
        override fun enqueue(text: String) { spoken.add(text) }
        override fun stop() { stopped = true }
        override suspend fun awaitIdle() {}
    }

    private class FakeAsr(private val text: String, private val error: Exception? = null) : AsrEngine {
        override suspend fun transcribe(pcm16: ByteArray, sampleRate: Int): Result<String> =
            error?.let { Result.failure(it) } ?: Result.success(text)
    }

    @Test
    fun `级联全通 - 句切分入 TTS 且历史落账`() = runTest {
        val tts = FakeTts()
        val engine = ConversationEngine(
            FakeLlm(listOf(
                LlmEvent.Delta("你好"), LlmEvent.Delta("！今天"), LlmEvent.Delta("天气不错。"),
                LlmEvent.Completed("stop"),
            )),
            tts,
        )
        engine.runTurn("你好", "k", "https://x/v1", "m")
        assertEquals(listOf("你好！", "今天天气不错。"), tts.spoken)
        assertEquals(ConvState.Idle, engine.state.value)
        assertEquals(1, engine.history.value.size)
        assertEquals("你好", engine.history.value[0].user)
        assertEquals("你好！今天天气不错。", engine.history.value[0].reply)
    }

    @Test
    fun `无标点尾巴 flush 兜底播报`() = runTest {
        val tts = FakeTts()
        val engine = ConversationEngine(
            FakeLlm(listOf(LlmEvent.Delta("答案是没有句号的结尾"), LlmEvent.Completed(null))),
            tts,
        )
        engine.runTurn("q", "k", "u", "m")
        assertEquals(listOf("答案是没有句号的结尾"), tts.spoken)
    }

    @Test
    fun `LLM 失败转 Failed 且停播`() = runTest {
        val tts = FakeTts()
        val engine = ConversationEngine(FakeLlm(listOf(LlmEvent.Failed("HTTP 401"))), tts)
        engine.runTurn("hi", "k", "https://x/v1", "m")
        assertTrue(engine.state.value is ConvState.Failed)
        assertTrue(tts.stopped)
    }

    @Test
    fun `语音轮 ASR 失败不进对话`() = runTest {
        val tts = FakeTts()
        val engine = ConversationEngine(FakeLlm(emptyList()), tts)
        engine.runVoiceTurn(ByteArray(100), 16_000, FakeAsr("", Exception("网络断")), "k", "u", "m")
        assertTrue(engine.state.value is ConvState.Failed)
        assertEquals(0, engine.history.value.size)
    }

    @Test
    fun `语音轮 ASR 成功进入对话`() = runTest {
        val tts = FakeTts()
        val engine = ConversationEngine(
            FakeLlm(listOf(LlmEvent.Delta("好的。"), LlmEvent.Completed("stop"))),
            tts,
        )
        engine.runVoiceTurn(ByteArray(100), 16_000, FakeAsr("几点了"), "k", "u", "m")
        assertEquals(listOf("好的。"), tts.spoken)
        assertEquals(ConvState.Idle, engine.state.value)
        assertEquals("几点了", engine.history.value[0].user)
    }

    @Test
    fun `Markdown 会被清洗后才进 TTS`() = runTest {
        val tts = FakeTts()
        val engine = ConversationEngine(
            FakeLlm(listOf(LlmEvent.Delta("**重点**：先吃饭。"), LlmEvent.Completed("stop"))),
            tts,
        )
        engine.runTurn("q", "k", "u", "m")
        assertEquals(listOf("重点：先吃饭。"), tts.spoken)
    }
}
