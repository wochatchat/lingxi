package com.lingxi.data.agent

import com.lingxi.data.ConversationEngine
import com.lingxi.data.LlmStream
import com.lingxi.data.Notifier
import com.lingxi.data.ProviderRepository
import com.lingxi.data.delegation.DelegationRepository
import com.lingxi.data.delegation.TaskKind
import com.lingxi.data.inspection.InspectionAgent
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F14 巡检 agent 提示词（计划-执行-自检 的行为约束都在这里）。
 */
object AgentPrompts {

    val SYSTEM = """
        你是灵犀的后台巡检代理，替一位手机用户定期检查信息并决定是否值得打扰他。
        工作方式：第一步先用一两句话在心里列出计划（直接写在回复里也行），
        中间步骤调用工具收集信息（优先 check_task 了解委托任务进展），
        最后一步给出面向用户的最终结论。
        规则：
        1. 结论必须是口语化中文，不超过三句，直接说重点，不用 Markdown 和表情。
        2. 只有「用户需要知道或需要行动」的变化才通知； routine 无变化 → 最终只回复 NOREPORT。
        3. 不要为了用工具而用工具；一般 1-3 步就应有结论。
        4. 你不能代替用户做决定：不要创建新任务除非巡检理由明确要求；不发送短信。
    """.trimIndent()

    private val timeFmt = DateTimeFormatter.ofPattern("M月d日 HH:mm")

    /** 组装一次巡检的任务说明（纯函数，可单测） */
    fun buildTaskPrompt(reason: String?, taskLines: List<String>, now: LocalDateTime): String {
        val sb = StringBuilder()
        sb.append("巡检时间：").append(now.format(timeFmt)).append("\n")
        if (!reason.isNullOrBlank()) {
            sb.append("触发原因：").append(reason).append("\n")
        }
        if (taskLines.isEmpty()) {
            sb.append("当前没有进行中的委托任务。\n")
        } else {
            sb.append("进行中的委托任务及其最近结果：\n").append(taskLines.joinToString("\n")).append("\n")
        }
        sb.append("请先判断：有没有需要跟进或提醒用户的事项？")
        sb.append("必要时用 check_task 查看任务详情、manage_task 收尾异常任务、web_search 核实公开信息。")
        sb.append("若一切平静、没有新发现，最终只回复 ${AgentLoop.NO_REPORT}，不要调用 notify_user。")
        return sb.toString()
    }
}

/**
 * F14 LLM 巡检 agent（R12 落地 InspectionAgent）：
 * 周期巡检（InspectionWorker）与委托完结即时触发共用本实现；
 * 结果 → 系统通知 + 对话历史落史（主动卡片）。
 */
@javax.inject.Singleton
class LlmInspectionAgent @javax.inject.Inject constructor(
    private val llm: LlmStream,
    private val toolbox: AgentToolbox,
    private val providers: ProviderRepository,
    private val delegation: DelegationRepository,
    private val notifier: Notifier,
    private val engine: ConversationEngine,
) : com.lingxi.data.inspection.InspectionAgent {

    override suspend fun inspect(reason: String?): String? {
        // 全离线 / 无可用 Provider：不跑（保持安静）
        if (engine.offlineMode) return null
        val enabled = runCatching { providers.firstEnabled() }.getOrNull() ?: return null
        val (config, apiKey) = enabled

        val tasks = runCatching { delegation.activeTasks() }.getOrDefault(emptyList())
        val taskLines = tasks.map { t ->
            val kind = when (t.kind) {
                com.lingxi.data.delegation.TaskKind.REMINDER -> "一次性提醒"
                else -> "每${t.intervalMinutes}分钟巡查"
            }
            "- #${t.id} ${t.title}（$kind，${t.status}）最近结果：${t.lastResult.ifBlank { "尚无" }}"
        }
        val taskPrompt = AgentPrompts.buildTaskPrompt(reason, taskLines, LocalDateTime.now())

        val outcome = AgentLoop(llm, toolbox::run).run(
            AgentRunConfig(
                apiKey = apiKey,
                baseUrl = config.baseUrl,
                model = config.model,
                systemPrompt = AgentPrompts.SYSTEM,
                task = taskPrompt,
                tools = toolbox.tools,
            ),
        )
        val summary = outcome.toUserSummary() ?: return null
        notifier.post("灵犀巡检", summary)
        engine.recordProactiveTurn("巡检", summary)
        return summary
    }
}
