package com.lingxi.data.agent

import com.lingxi.data.ChatMessage
import com.lingxi.data.LlmEvent
import com.lingxi.data.LlmStream
import com.lingxi.data.functions.ToolSpec

/**
 * F14 Agent Loop（R12，参考 Minis scheduled+tools 与 MonkeyCode「计划-执行-自检」）：
 * LLM 多步工具循环（默认上限 6 步）+ 步数用尽后的自检总结。
 *
 * 与对话引擎的单发 tool_call 不同，本循环把每一步的工具执行结果以文本形式
 * 拼回上下文再问下一轮（规避 OpenAI tool_calls 消息回填的协议兼容面），
 * 因此循环逻辑纯 Kotlin 可单测，Android 依赖只通过 [runTool] 注入。
 */

/** 单个工具的执行结果（喂回 LLM 的文本摘要） */
data class AgentToolResult(val success: Boolean, val summary: String)

/** 一次 agent 运行的产出 */
data class AgentOutcome(
    /** 最终结论（用户可读）；null = 无结论（失败或超步数且总结也为空） */
    val summary: String?,
    val stepsUsed: Int,
    /** 每步工具执行记录（审计/落史用） */
    val toolLog: List<String> = emptyList(),
    val error: String? = null,
) {
    /** 映射为「是否值得打扰用户」的用户文案：无结论/无变化 → null */
    fun toUserSummary(): String? {
        val s = summary?.trim().orEmpty()
        if (s.isEmpty()) return null
        if (s.uppercase().contains(AgentLoop.NO_REPORT)) return null
        return s
    }
}

/** 一次 agent 运行的配置（凭据与任务由调用方组装） */
data class AgentRunConfig(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val systemPrompt: String,
    val task: String,
    val tools: List<ToolSpec>,
    val maxSteps: Int = 6,
)

class AgentLoop(
    private val llm: com.lingxi.data.LlmStream,
    private val runTool: suspend (name: String, argsJson: String) -> AgentToolResult,
) {

    /**
     * 执行循环：每步一次 LLM 调用（带工具定义）；
     * 有 tool_call → 执行并记录 → 下一步；纯文本 → 视为最终结论。
     * 步数用尽 → 追加一次无工具的自检总结调用。
     */
    suspend fun run(cfg: AgentRunConfig): AgentOutcome {
        val log = mutableListOf<String>()
        var step = 0
        while (step < cfg.maxSteps) {
            step++
            val messages = buildStepMessages(cfg.systemPrompt, cfg.task, log)
            val (text0, toolCalls0) = callOnce(cfg, messages, cfg.tools).getOrElse { err ->
                return AgentOutcome(null, step, log.toList(), error = err.message)
            }
            if (toolCalls0.isEmpty()) {
                val text = text0.trim()
                if (text.isNotEmpty()) return AgentOutcome(text, step, log.toList())
                continue // 空响应：再给一次机会
            }
            for (tc in toolCalls0) {
                val result = runCatching { runTool(tc.name, tc.argsJson) }.getOrElse {
                    AgentToolResult(false, "执行异常: ${it.message ?: it.javaClass.simpleName}")
                }
                log.add(toolLogLine(log.size + 1, tc.name, tc.argsJson, result))
            }
        }
        // 自检总结：步数用尽，强制收敛（不带工具，防继续打转）
        val finalMessages = buildStepMessages(cfg.systemPrompt, cfg.task, log, forceSummary = true)
        val (finalText, _) = callOnce(cfg, finalMessages, tools = null).getOrElse { err ->
            return AgentOutcome(null, step, log.toList(), error = err.message)
        }
        return AgentOutcome(finalText.trim().ifBlank { null }, step, log.toList())
    }

    /** 单步 LLM 调用：聚合增量文本与全部 tool_call */
    private suspend fun callOnce(
        cfg: AgentRunConfig,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>?,
    ): Result<Pair<String, List<LlmEvent.ToolCall>>> {
        val text = StringBuilder()
        val calls = mutableListOf<LlmEvent.ToolCall>()
        var failure: LlmEvent.Failed? = null
        llm.streamChat(cfg.apiKey, cfg.baseUrl, cfg.model, messages, tools = tools).collect { ev ->
            when (ev) {
                is LlmEvent.Delta -> text.append(ev.text)
                is LlmEvent.ToolCall -> calls.add(ev)
                is LlmEvent.Failed -> failure = ev
                is LlmEvent.Completed -> Unit
            }
        }
        failure?.let { return Result.failure(IllegalStateException(it.message)) }
        return Result.success(text.toString() to calls)
    }

    companion object {
        /** 无打扰哨兵：最终结论含此关键词 → 不通知用户 */
        const val NO_REPORT = "NOREPORT"

        /** 一步工具执行的审计行（纯函数，测试快照用） */
        fun toolLogLine(index: Int, tool: String, argsJson: String, result: AgentToolResult): String {
            val argsBrief = argsJson.replace("\n", " ").take(80)
            val status = if (result.success) "成功" else "失败"
            return "[步骤$index] 调用 $tool($argsBrief) → $status: ${result.summary.take(200)}"
        }

        /**
         * 组装一步的消息序列（纯函数）：
         * system + 任务 + 工具执行记录（assistant/user 交替），forceSummary 时明确要求收尾结论。
         */
        fun buildStepMessages(
            systemPrompt: String,
            task: String,
            toolLog: List<String>,
            forceSummary: Boolean = false,
        ): List<ChatMessage> {
            val messages = mutableListOf(
                ChatMessage("system", systemPrompt),
                ChatMessage("user", task),
            )
            if (toolLog.isNotEmpty()) {
                messages.add(ChatMessage("assistant", "我已按步骤执行工具，记录如下：\n" + toolLog.joinToString("\n")))
                val ask = if (forceSummary) {
                    "工具步数已用完。请基于以上记录做自检总结，直接给出面向用户的最终结论（中文口语，不超过三句）；" +
                        "没有值得打扰用户的发现就只回复 $NO_REPORT。不要再调用工具。"
                } else {
                    "继续。信息足够就直接给出最终结论（含 $NO_REPORT 语义则只回复 $NO_REPORT）；否则调用下一步工具。"
                }
                messages.add(ChatMessage("user", ask))
            }
            return messages
        }
    }
}
