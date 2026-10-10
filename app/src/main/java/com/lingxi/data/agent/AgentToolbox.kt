package com.lingxi.data.agent

import com.lingxi.data.Notifier
import com.lingxi.data.delegation.DelegationRepository
import com.lingxi.data.delegation.TaskKind
import com.lingxi.data.functions.ActionExecutor
import com.lingxi.data.functions.ToolDefs
import com.lingxi.data.functions.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent 工具箱（R12 F14）：巡检 agent 专属的受限工具面。
 * 仅暴露后台可安全执行的工具（无短信/代点/交互卡片），
 * manage_task/web_search/read_screen 复用对话引擎的 ActionExecutor 实现。
 */
@javax.inject.Singleton
class AgentToolbox @Inject constructor(
    private val delegation: com.lingxi.data.delegation.DelegationRepository,
    private val notifier: com.lingxi.data.Notifier,
    private val executor: com.lingxi.data.functions.ActionExecutor,
) {

    /** agent 可用工具集（复用 ToolDefs + 两个 agent 专属工具） */
    val tools: List<com.lingxi.data.functions.ToolSpec> = buildList {
        ToolDefs.byName("manage_task")?.let { add(it) }
        ToolDefs.byName("web_search")?.let { add(it) }
        ToolDefs.byName("read_screen")?.let { add(it) }
        add(ToolSpec(
            name = "notify_user",
            description = "给用户发一条系统通知（巡检 agent 触达用户的唯一通道）。参数 title 为通知标题，body 为正文。无值得打扰的内容时不要调用。",
            parametersJson = """
                {
                  "type": "object",
                  "properties": {
                    "title": { "type": "string", "description": "通知标题，简短" },
                    "body": { "type": "string", "description": "通知正文，一句话说清发现" }
                  },
                  "required": ["title", "body"]
                }
            """.trimIndent(),
        ))
        add(ToolSpec(
            name = "check_task",
            description = "查询委托任务的最新进展（只读，不产生打扰）。参数 id 为任务 id（可选）；缺省列出全部进行中任务及其最近结果。",
            parametersJson = """
                {
                  "type": "object",
                  "properties": {
                    "id": { "type": "integer", "description": "任务 id（可选，缺省列出全部进行中任务）" }
                  },
                  "required": []
                }
            """.trimIndent(),
        ))
    }

    /** 工具执行入口（注入 AgentLoop 的 runTool） */
    suspend fun run(name: String, argsJson: String): AgentToolResult = when (name) {
        "notify_user" -> notifyUser(argsJson)
        "check_task" -> checkTask(argsJson)
        "manage_task", "web_search", "read_screen" -> {
            val r = runCatching { executor.execute(name, argsJson) }.getOrElse {
                return AgentToolResult(false, "执行失败: ${it.message ?: it.javaClass.simpleName}")
            }
            val summary = when {
                !r.success -> r.errorMessage ?: "执行失败"
                r.spoken.isNotBlank() -> r.spoken
                else -> r.cardBody ?: "完成"
            }
            AgentToolResult(r.success, summary)
        }
        else -> AgentToolResult(false, "未知工具: $name（可用: notify_user / check_task / manage_task / web_search / read_screen）")
    }

    /** 主动通知：agent 打扰用户的正规通道（无 UI 门，仅通知） */
    private suspend fun notifyUser(argsJson: String): AgentToolResult {
        val args = parseArgs(argsJson)
        val title = args["title"]?.trim().orEmpty()
        val body = args["body"]?.trim().orEmpty()
        if (title.isBlank() || body.isBlank()) return AgentToolResult(false, "缺少 title 或 body")
        notifier.post(title, body)
        return AgentToolResult(true, "已通知用户：$title")
    }

    /** 只读查询：进行中委托任务 + 最近结果（不产生副作用） */
    private suspend fun checkTask(argsJson: String): AgentToolResult {
        val args = parseArgs(argsJson)
        val id = args["id"]?.trim()?.toLongOrNull()
        val tasks = when {
            id != null -> listOfNotNull(runCatching { delegation.byId(id) }.getOrNull())
            else -> runCatching { delegation.activeTasks() }.getOrDefault(emptyList())
        }
        if (tasks.isEmpty()) return AgentToolResult(true, "当前没有可查询的委托任务")
        val lines = tasks.map { t ->
            val kind = when (t.kind) {
                TaskKind.REMINDER -> "一次性提醒"
                else -> "每${t.intervalMinutes}分钟巡查"
            }
            val last = t.lastResult.ifBlank { "尚无结果" }
            "- #${t.id} ${t.title}（$kind，${t.status}）最近结果：$last"
        }
        return AgentToolResult(true, "共 ${tasks.size} 个任务：\n" + lines.joinToString("\n"))
    }

    private fun parseArgs(json: String): Map<String, String> = runCatching {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{")) return emptyMap()
        val obj = Json.parseToJsonElement(trimmed).jsonObject
        obj.entries.associate { it.key to (it.value.jsonPrimitive.contentOrNull ?: "") }
    }.getOrDefault(emptyMap())
}
