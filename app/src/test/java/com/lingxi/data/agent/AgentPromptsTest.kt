package com.lingxi.data.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.Month

class AgentPromptsTest {

    @Test
    fun `buildTaskPrompt 无任务时提示无任务`() {
        val now = LocalDateTime.of(2025, Month.OCTOBER, 10, 8, 30)
        val prompt = AgentPrompts.buildTaskPrompt(reason = null, taskLines = emptyList(), now = now)
        assertTrue(prompt.contains("10月10日 08:30"))
        assertTrue(prompt.contains("当前没有进行中的委托任务"))
    }

    @Test
    fun `buildTaskPrompt 带触发原因`() {
        val now = LocalDateTime.of(2025, Month.OCTOBER, 10, 9, 0)
        val prompt = AgentPrompts.buildTaskPrompt(
            reason = "委托「顺丰快递」刚完结（已签收），请评估是否有需要提醒用户的",
            taskLines = listOf(
                "- #1 顺丰快递（每60分钟巡查，ACTIVE）最近结果：已签收",
            ),
            now = now,
        )
        assertTrue(prompt.contains("触发原因"))
        assertTrue(prompt.contains("刚完结"))
        assertTrue(prompt.contains("委托「顺丰快递」"))
        assertTrue(prompt.contains("进行中的委托任务"))
    }

    @Test
    fun `buildTaskPrompt 含 NOREPORT 指令`() {
        val prompt = AgentPrompts.buildTaskPrompt(null, emptyList(), LocalDateTime.now())
        assertTrue(prompt.contains(AgentLoop.NO_REPORT))
        assertTrue(prompt.contains("notify_user"))
        assertTrue(prompt.contains("不要调用"))
    }

    @Test
    fun `buildTaskPrompt 有多个任务时列出全部`() {
        val lines = listOf(
            "- #1 顺丰快递（每60分钟巡查，ACTIVE）最近结果：已签收",
            "- #2 打坐提醒（一次性提醒，DONE）最近结果：已提醒",
        )
        val prompt = AgentPrompts.buildTaskPrompt(null, lines, LocalDateTime.now())
        assertTrue(prompt.contains("#1"))
        assertTrue(prompt.contains("#2"))
        assertTrue(prompt.contains("顺丰快递"))
        assertTrue(prompt.contains("打坐提醒"))
    }
}