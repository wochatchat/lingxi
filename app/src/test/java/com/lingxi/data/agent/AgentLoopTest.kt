package com.lingxi.data.agent

import com.lingxi.data.ChatMessage
import com.lingxi.data.LlmEvent
import com.lingxi.data.LlmStream
import com.lingxi.data.functions.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {

    // Fake LLM：事件序列回放
    private class FakeLlm(private val sequence: List<List<LlmEvent>>) : LlmStream {
        var callCount = 0
            private set
        var lastMessages: List<ChatMessage> = emptyList()
        var lastTools: List<ToolSpec>? = null

        override fun streamChat(
            apiKey: String,
            baseUrl: String,
            model: String,
            messages: List<ChatMessage>,
            temperature: Double,
            tools: List<ToolSpec>?,
        ): Flow<LlmEvent> = flow {
            lastMessages = messages
            lastTools = tools
            callCount++
            for (ev in sequence[callCount - 1]) emit(ev)
        }
    }

    // Fake runTool：按工具名返回固定结果
    private fun fakeTool(vararg results: Pair<String, AgentToolResult>): suspend (String, String) -> AgentToolResult =
        { name, _ -> results.find { it.first == name }?.second ?: AgentToolResult(false, "未知: $name") }

    // 单步 text 结果
    private val singleText = listOf(listOf(LlmEvent.Delta("今天天气不错，无异常")))
    // 工具→工具→text
    private val toolTextText = listOf(
        listOf(LlmEvent.ToolCall("check_task", "{}")),
        listOf(LlmEvent.ToolCall("check_task", "{}")),
        listOf(LlmEvent.Delta("无新变化")),
    )
    // maxSteps 超限：所有轮都 tool_call → 最后强制总结
    private val toolOnlySequence = listOf(
        listOf(LlmEvent.ToolCall("check_task", "{}")),
        listOf(LlmEvent.ToolCall("check_task", "{}")),
        listOf(LlmEvent.Delta("例行检查，一切正常")), // forceSummary call
    )
    // LLM 失败
    private val failedSequence = listOf(listOf(LlmEvent.Failed("网络错误")))

    private fun cfg(tools: List<ToolSpec> = emptyList()) = AgentRunConfig(
        apiKey = "k",
        baseUrl = "https://api.example.com",
        model = "gpt-4",
        systemPrompt = "你是助手",
        task = "检查天气",
        tools = tools,
        maxSteps = 3,
    )

    @Test
    fun `纯文本 → 直接结论`() = runTest {
        val llm = FakeLlm(singleText)
        val loop = AgentLoop(llm, fakeTool())
        val outcome = loop.run(cfg())
        assertEquals("今天天气不错，无异常", outcome.summary)
        assertEquals(1, outcome.stepsUsed)
        assertTrue(outcome.toolLog.isEmpty())
        assertNull(outcome.error)
    }

    @Test
    fun `工具调用链 → 最终结论`() = runTest {
        val tools = listOf(
            ToolSpec("check_task", "查任务", """{"type":"object","properties":{},"required":[]}"""),
        )
        val llm = FakeLlm(toolTextText)
        val loop = AgentLoop(llm) { name, _ ->
            if (name == "check_task") AgentToolResult(true, "2 个任务均无新进展")
            else AgentToolResult(false, "unknown")
        }
        val outcome = loop.run(cfg(tools))
        assertEquals("无新变化", outcome.summary)
        assertEquals(3, outcome.stepsUsed)
        assertEquals(2, outcome.toolLog.size)
        assertTrue(outcome.toolLog[0].contains("步骤1"))
        assertTrue(outcome.toolLog[0].contains("check_task"))
        assertTrue(outcome.toolLog[0].contains("成功"))
    }

    @Test
    fun `步数耗尽 → 强制自检总结`() = runTest {
        val tools = listOf(
            ToolSpec("check_task", "查任务", """{"type":"object","properties":{},"required":[]}"""),
        )
        // maxSteps=2：步1工具→步2工具→loop退出→forceSummary（无工具）
        val llm = FakeLlm(toolOnlySequence) // 2 tool + 1 text(forceSummary)
        val loop = AgentLoop(llm) { _, _ -> AgentToolResult(true, "tool ok") }
        val outcome = loop.run(cfg(tools).copy(maxSteps = 2))
        assertEquals(3, outcome.stepsUsed) // 2 tool steps + 1 force summary
        assertEquals("例行检查，一切正常", outcome.summary)
        // 最后一次调用（forceSummary）不带工具
        assertTrue(llm.lastTools == null || llm.lastTools?.isEmpty() == true)
    }
        val tools = listOf(
            ToolSpec("check_task", "查任务", """{"type":"object","properties":{},"required":[]}"""),
        )
        val llm = FakeLlm(toolOnlySequence)
        val loop = AgentLoop(llm) { _, _ -> AgentToolResult(true, "tool ok") }
        val outcome = loop.run(cfg(tools).copy(maxSteps = 2))
        assertEquals(3, outcome.stepsUsed) // 2 tool steps + 1 force summary
        assertEquals("例行检查，一切正常", outcome.summary)
        // 最后一次调用应无工具（强制总结）
        assertTrue(llm.lastTools == null)
    }

    @Test
    fun `LLM 失败 → error 非 null`() = runTest {
        val llm = FakeLlm(failedSequence)
        val loop = AgentLoop(llm, fakeTool())
        val outcome = loop.run(cfg())
        assertNull(outcome.summary)
        assertNotNull(outcome.error)
        assertTrue(outcome.error!!.contains("网络错误"))
    }

    @Test
    fun `NOREPORT 哨兵 → toUserSummary 返回 null`() = runTest {
        val llm = FakeLlm(listOf(listOf(LlmEvent.Delta("NOREPORT"))))
        val outcome = AgentLoop(llm, fakeTool()).run(cfg())
        assertEquals("NOREPORT", outcome.summary)
        assertNull(outcome.toUserSummary())
    }

    @Test
    fun `buildStepMessages 空日志 → system + task`() {
        val msgs = AgentLoop.buildStepMessages("sys", "task", emptyList())
        assertEquals(2, msgs.size)
        assertEquals("system", msgs[0].role)
        assertEquals("sys", msgs[0].content)
        assertEquals("user", msgs[1].role)
        assertEquals("task", msgs[1].content)
    }

    @Test
    fun `buildStepMessages 有日志 → 追加 assistant user 交替`() {
        val log = listOf(
            "[步骤1] 调用 check_task({}) → 成功: 2 个任务",
            "[步骤2] 调用 web_search(天气) → 成功: 无异常",
        )
        val msgs = AgentLoop.buildStepMessages("sys", "task", log)
        assertEquals(4, msgs.size)
        assertEquals("assistant", msgs[2].role)
        assertTrue(msgs[2].content.contains("步骤1"))
        assertEquals("user", msgs[3].role)
        assertTrue(msgs[3].content.contains("继续"))
    }

    @Test
    fun `buildStepMessages forceSummary → 明确要求结论`() {
        val log = listOf("[步骤1] 调用 x → ok")
        val msgs = AgentLoop.buildStepMessages("sys", "task", log, forceSummary = true)
        val lastUser = msgs.last()
        assertTrue(lastUser.content.contains("步数已用完"))
        assertTrue(lastUser.content.contains("不要调用工具"))
    }

    @Test
    fun `toolLogLine 格式正确`() {
        val result = AgentToolResult(true, "任务正常")
        val line = AgentLoop.toolLogLine(2, "check_task", """{"id":123}""", result)
        assertTrue(line.contains("[步骤2]"))
        assertTrue(line.contains("check_task"))
        assertTrue(line.contains("id:123"))
        assertTrue(line.contains("成功"))
        assertTrue(line.contains("任务正常"))
    }

    @Test
    fun `toolLogLine 失败状态`() {
        val result = AgentToolResult(false, "网络超时")
        val line = AgentLoop.toolLogLine(1, "web_search", """{"query":"test"}""", result)
        assertTrue(line.contains("失败"))
        assertTrue(line.contains("网络超时"))
    }
}