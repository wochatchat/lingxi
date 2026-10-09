package com.lingxi

import com.lingxi.data.AsrEngine
import com.lingxi.data.ChatMessage
import com.lingxi.data.ConvState
import com.lingxi.data.ConversationEngine
import com.lingxi.data.LlmEvent
import com.lingxi.data.LlmStream
import com.lingxi.data.TtsEngine
import com.lingxi.data.memory.DailySummary
import com.lingxi.data.memory.MemoryStore
import com.lingxi.data.memory.StoredTurn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** 记录型记忆门面：可预置画像/摘要/历史，记录落库调用 */
    private class FakeMemoryStore(
        val profile: List<String> = emptyList(),
        val summaries: List<DailySummary> = emptyList(),
        val preloaded: List<StoredTurn> = emptyList(),
    ) : MemoryStore {
        val appended = mutableListOf<Pair<String, String>>()
        override suspend fun loadRecentTurns(limit: Int): List<StoredTurn> = preloaded.takeLast(limit)
        override suspend fun profileLines(): List<String> = profile
        override suspend fun recentDailySummaries(limit: Int): List<DailySummary> = summaries.takeLast(limit)
        override suspend fun onTurnCompleted(user: String, reply: String) { appended.add(user to reply) }
    }

    @Test
    fun `级联全通 - 句切分入 TTS 且历史落账`() = runTest {
        val tts = FakeTts()
        val memory = FakeMemoryStore()
        val engine = ConversationEngine(
            FakeLlm(listOf(
                LlmEvent.Delta("你好"), LlmEvent.Delta("！今天"), LlmEvent.Delta("天气不错。"),
                LlmEvent.Completed("stop"),
            )),
            tts,
            memory,
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
            FakeMemoryStore(),
        )
        engine.runTurn("q", "k", "u", "m")
        assertEquals(listOf("答案是没有句号的结尾"), tts.spoken)
    }

    @Test
    fun `LLM 失败转 Failed 且停播`() = runTest {
        val tts = FakeTts()
        val engine = ConversationEngine(FakeLlm(listOf(LlmEvent.Failed("HTTP 401"))), tts, FakeMemoryStore())
        engine.runTurn("hi", "k", "https://x/v1", "m")
        assertTrue(engine.state.value is ConvState.Failed)
        assertTrue(tts.stopped)
    }

    @Test
    fun `语音轮 ASR 失败不进对话`() = runTest {
        val tts = FakeTts()
        val engine = ConversationEngine(FakeLlm(emptyList()), tts, FakeMemoryStore())
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
            FakeMemoryStore(),
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
            FakeMemoryStore(),
        )
        engine.runTurn("q", "k", "u", "m")
        assertEquals(listOf("重点：先吃饭。"), tts.spoken)
    }

    // ---- F7 三层记忆 ----

    @Test
    fun `画像与摘要注入 system prompt`() = runTest {
        val tts = FakeTts()
        val memory = FakeMemoryStore(
            profile = listOf("用户名叫阿哲", "用户希望结论先行"),
            summaries = listOf(DailySummary("2026-10-08", "- 讨论了周报结构")),
        )
        val llm = FakeLlm(listOf(LlmEvent.Delta("好的。"), LlmEvent.Completed("stop")))
        val engine = ConversationEngine(llm, tts, memory)
        engine.runTurn("q", "k", "u", "m")
        val system = llm.lastMessages.first()
        assertEquals("system", system.role)
        assertTrue(system.content.contains("用户名叫阿哲"))
        assertTrue(system.content.contains("结论先行"))
        assertTrue(system.content.contains("2026-10-08"))
        // 画像只进 system，不污染 user 消息
        assertFalse(llm.lastMessages.any { it.role == "user" && it.content.contains("阿哲") })
    }

    @Test
    fun `重启后从记忆恢复历史滑窗与 UI 历史`() = runTest {
        val tts = FakeTts()
        val memory = FakeMemoryStore(
            preloaded = listOf(
                StoredTurn("昨天聊了什么", "聊了天气"),
                StoredTurn("我姓王", "好的王先生"),
            ),
        )
        val llm = FakeLlm(listOf(LlmEvent.Delta("嗯。"), LlmEvent.Completed("stop")))
        val engine = ConversationEngine(llm, tts, memory)
        engine.runTurn("继续", "k", "u", "m")
        // system + 2 轮历史（4 条）+ 本轮 user = 6 条
        assertEquals(6, llm.lastMessages.size)
        assertEquals("user", llm.lastMessages[1].role)
        assertEquals("昨天聊了什么", llm.lastMessages[1].content)
        assertEquals("assistant", llm.lastMessages[2].role)
        assertEquals("好的王先生", llm.lastMessages[3].content)
        assertEquals(2, engine.history.value.size)
    }

    @Test
    fun `对话完成触发记忆落库`() = runTest {
        val tts = FakeTts()
        val memory = FakeMemoryStore()
        val engine = ConversationEngine(
            FakeLlm(listOf(LlmEvent.Delta("好的。"), LlmEvent.Completed("stop"))),
            tts,
            memory,
        )
        engine.runTurn("记住我叫阿哲", "k", "u", "m")
        assertEquals(1, memory.appended.size)
        assertEquals("记住我叫阿哲", memory.appended[0].first)
    }

    @Test
    fun `LLM 失败不落记忆`() = runTest {
        val tts = FakeTts()
        val memory = FakeMemoryStore()
        val engine = ConversationEngine(FakeLlm(listOf(LlmEvent.Failed("HTTP 401"))), tts, memory)
        engine.runTurn("hi", "k", "u", "m")
        assertEquals(0, memory.appended.size)
    }
}
