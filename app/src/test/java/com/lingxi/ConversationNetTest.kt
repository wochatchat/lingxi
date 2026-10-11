package com.lingxi

import com.lingxi.data.ConvState
import com.lingxi.data.ConversationEngine
import com.lingxi.data.functions.ActionExecutor
import com.lingxi.data.functions.ActionResult
import com.lingxi.data.net.NetStatus
import com.lingxi.data.net.NetTier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R15 网络三档 / 未配置服务 / 系统识别兜底的引擎级用例 */
class ConversationNetTest {

    private class FakeTts : com.lingxi.data.TtsEngine {
        val spoken = mutableListOf<String>()
        override val ready: Boolean get() = true
        override fun enqueue(text: String) { spoken.add(text) }
        override fun stop() {}
        override suspend fun awaitIdle() {}
    }

    private class FakeNet(private val tier: com.lingxi.data.net.NetTier) : NetStatus {
        override fun current(): NetTier = tier
    }

    private class FakeLlm : com.lingxi.data.LlmStream {
        var called = false
        var lastSystemPrompt: String = ""
        override fun streamChat(
            apiKey: String,
            baseUrl: String,
            model: String,
            messages: List<com.lingxi.data.ChatMessage>,
            temperature: Double,
            tools: List<com.lingxi.data.functions.ToolSpec>?,
        ): kotlinx.coroutines.flow.Flow<com.lingxi.data.LlmEvent> = kotlinx.coroutines.flow.flow {
            called = true
            lastSystemPrompt = messages.firstOrNull { it.role == "system" }?.content ?: ""
            emit(com.lingxi.data.LlmEvent.Completed("stop"))
        }
    }

    private class FakeMemoryStore : com.lingxi.data.memory.MemoryStore {
        override suspend fun loadRecentTurns(limit: Int) = emptyList<com.lingxi.data.memory.StoredTurn>()
        override suspend fun profileLines() = emptyList<String>()
        override suspend fun recentDailySummaries(limit: Int) = emptyList<com.lingxi.data.memory.DailySummary>()
        override suspend fun onTurnCompleted(user: String, reply: String) {}
        override suspend fun onBehaviorObserved(action: String, atMs: Long) {}
        override suspend fun topInterests(limit: Int) = emptyList<String>()
    }

    private class FakeExecutor : ActionExecutor {
        override suspend fun execute(toolName: String, argsJson: String) =
            com.lingxi.data.functions.ActionResult.ok("已完成")
    }

    private fun engine(net: NetStatus, llm: FakeLlm) = ConversationEngine(
        llm, FakeTts(), FakeMemoryStore(), FakeExecutor(),
        object : com.lingxi.data.screen.ScreenCaptor {
            override fun hasPermission() = true
            override suspend fun latestScreenshot(): com.lingxi.data.screen.ScreenImage? = null
        },
        net,
    )

    @Test
    fun `断网时闲聊给断网占位且不调 LLM`() = runTest {
        val llm = FakeLlm()
        val tts = FakeTts()
        val engine = ConversationEngine(
            llm, tts, FakeMemoryStore(), FakeExecutor(),
            object : com.lingxi.data.screen.ScreenCaptor {
                override fun hasPermission() = true
                override suspend fun latestScreenshot(): com.lingxi.data.screen.ScreenImage? = null
            },
            FakeNet(NetTier.OFFLINE),
        )
        engine.runTurn("今天天气怎么样", "k", "u", "m")
        assertTrue(tts.spoken.single().contains("没有网络"))
        assertFalse(llm.called)
        assertEquals(ConvState.Idle, engine.state.value)
    }

    @Test
    fun `弱网 - system prompt 追加短回复约束`() = runTest {
        val llm = FakeLlm()
        val engine = engine(FakeNet(NetTier.WEAK), llm)
        engine.runTurn("介绍下你自己", "k", "u", "m")
        assertTrue(llm.called)
        assertTrue(llm.lastSystemPrompt.contains("网络信号较弱"))
    }

    @Test
    fun `正常网络 - 不追加弱网约束`() = runTest {
        val llm = FakeLlm()
        val engine = engine(FakeNet(NetTier.ONLINE), llm)
        engine.runTurn("你好", "k", "u", "m")
        assertTrue(!llm.lastSystemPrompt.contains("网络信号较弱"))
    }

    @Test
    fun `未配置 AI 服务 - 空 apiKey 给友好提示且不调 LLM`() = runTest {
        val llm = FakeLlm()
        val tts = FakeTts()
        val e = ConversationEngine(
            llm, tts, FakeMemoryStore(), FakeExecutor(),
            object : com.lingxi.data.screen.ScreenCaptor {
                override fun hasPermission() = true
                override suspend fun latestScreenshot(): com.lingxi.data.screen.ScreenImage? = null
            },
        )
        e.runTurn("你好", "", "", "")
        assertTrue(tts.spoken.single().contains("还没配置"))
        assertTrue(!llm.called)
    }

    @Test
    fun `系统识别兜底 - 空文本置 Failed`() = runTest {
        val e = engine(FakeNet(NetTier.ONLINE), FakeLlm())
        e.runRecognizedText("", "", "", "")
        assertEquals(ConvState.Failed("", "未能识别语音，请再试一次"), e.state.value)
    }

    @Test
    fun `系统识别兜底 - 识别文本直接进对话轮`() = runTest {
        val llm = FakeLlm()
        val e = engine(FakeNet(NetTier.ONLINE), llm)
        e.runRecognizedText("今天几号", "", "", "")
        assertTrue(!llm.called) // 未配置 Provider → 友好兜底，不走 LLM
        assertTrue(e.history.value.last().reply.contains("还没配置"))
    }
}
