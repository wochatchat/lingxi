package com.lingxi.data.functions

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 系统动作执行器（F8 五个工具的 Android 实现）：
 * - set_alarm    → AlarmClock.ACTION_SET_ALARM（SKIP_UI=true，不弹设置界面）
 * - open_app     → PackageManager query → launch
 * - web_search   → AlarmClock.ACTION_WEB_SEARCH（系统默认搜索）
 * - send_sms     → Intent.ACTION_SENDTO（smsto: 预填收件人+正文）
 * - manage_task  → F13 委托任务（DelegationRepository 落库 + WorkManager 调度）
 */
@Singleton
class AndroidActionExecutor @Inject constructor(
    @ApplicationContext private val app: Context,
    private val delegation: com.lingxi.data.delegation.DelegationRepository,
    private val settings: com.lingxi.data.SettingsRepository,
) : ActionExecutor {

    override suspend fun execute(toolName: String, argsJson: String): ActionResult {
        return when (toolName) {
            "set_alarm" -> setAlarm(argsJson)
            "open_app" -> openApp(argsJson)
            "web_search" -> webSearch(argsJson)
            "send_sms" -> sendSms(argsJson)
            "manage_task" -> manageTask(argsJson)
            "read_screen" -> readScreen()
            "click_ui" -> clickUi(argsJson)
            else -> ActionResult.error("未知工具: $toolName")
        }
    }

    /** F9 闸门：软件总开关 + 系统无障碍服务都开才可用 */
    private suspend fun assistGate(): ActionResult? {
        if (!settings.uiAssistEnabled.first()) {
            return ActionResult.error("UI 代操作总开关未开启，可在 设置 → UI 代操作 里打开")
        }
        if (!com.lingxi.service.LingXiAccessibilityService.enabled) {
            return ActionResult.error("灵犀的无障碍服务未开启，请到系统设置 → 无障碍 里开启")
        }
        return null
    }

    /** F9 读屏：当前窗口可见文字（不执行任何操作，低风险） */
    private suspend fun readScreen(): ActionResult {
        assistGate()?.let { return it }
        val text = runCatching { com.lingxi.service.LingXiAccessibilityService.readScreenText() }
            .getOrElse { return ActionResult.error("读取屏幕失败：${it.message ?: "未知原因"}") }
        if (text.isNullOrBlank()) return ActionResult.error("当前屏幕没有可读的文字内容")
        return ActionResult.ok(
            "我看了下当前屏幕：" + text.lineSequence().take(6).joinToString("，").take(120),
            cardTitle = "当前屏幕内容",
            cardBody = text,
        ).also { logAudit("read_screen", "读取当前屏幕", true) }
    }

    /** F9 代点：点击包含指定文字的元素（引擎侧已先过 ConfirmGate） */
    private suspend fun clickUi(argsJson: String): ActionResult {
        assistGate()?.let { return it }
        val target = parseArgs(argsJson)["target"]?.trim().orEmpty()
        if (target.isBlank()) return ActionResult.error("缺少 target 参数")
        val ok = runCatching { com.lingxi.service.LingXiAccessibilityService.clickText(target) }
            .getOrElse { false }
        logAudit("click_ui", "点击「$target」", ok)
        return if (ok) {
            ActionResult.ok("已点击「$target」", cardTitle = "UI 代操作", cardBody = "已点击：$target")
        } else {
            ActionResult.error("当前屏幕没找到可点击的「$target」")
        }
    }

    private suspend fun logAudit(action: String, detail: String, success: Boolean) {
        runCatching { com.lingxi.data.assist.AuditLog.append(app, action, detail, success) }
    }

    /** F13 委托任务：create/list/cancel/pause/resume */
    private suspend fun manageTask(argsJson: String): ActionResult {
        val parsed = com.lingxi.data.delegation.DelegationArgs.parse(
            parseArgs(argsJson),
            System.currentTimeMillis(),
            java.time.ZoneId.systemDefault(),
        )
        val command = when (parsed) {
            is com.lingxi.data.delegation.DelegationArgs.Result.Ok -> parsed.command
            is com.lingxi.data.delegation.DelegationArgs.Result.Error -> return ActionResult.error(parsed.message)
        }
        return when (command) {
            is com.lingxi.data.delegation.DelegationArgs.Command.CreateReminder -> {
                val task = delegation.createReminder(command.title, command.atMillis)
                ActionResult.ok(
                    "好，${command.timeHuman}我会提醒你${command.title}",
                    cardTitle = "委托任务已创建",
                    cardBody = "${task.title}\n提醒时间：${command.timeHuman}",
                )
            }
            is com.lingxi.data.delegation.DelegationArgs.Command.CreatePoll -> {
                val task = delegation.createPoll(command.title, command.intervalMinutes)
                ActionResult.ok(
                    "收到，我会每${humanInterval(task.intervalMinutes)}盯一次「${command.title}」，有消息就告诉你",
                    cardTitle = "委托任务已创建",
                    cardBody = "${task.title}\n巡查间隔：每${humanInterval(task.intervalMinutes)}",
                )
            }
            is com.lingxi.data.delegation.DelegationArgs.Command.List -> {
                val tasks = delegation.activeTasks()
                if (tasks.isEmpty()) {
                    ActionResult.ok("现在没有进行中的委托任务", cardTitle = "委托任务", cardBody = "（无进行中的任务）")
                } else {
                    val lines = tasks.mapIndexed { i, t ->
                        val detail = when (t.kind) {
                            com.lingxi.data.delegation.TaskKind.REMINDER ->
                                "提醒 " + com.lingxi.data.delegation.TaskTime.formatAt(t.triggerAt, java.time.ZoneId.systemDefault())
                            else -> "每${humanInterval(t.intervalMinutes)}巡查"
                        }
                        "${i + 1}. ${t.title}（$detail）"
                    }
                    ActionResult.ok(
                        "有 ${tasks.size} 个进行中的委托：" + lines.joinToString("；"),
                        cardTitle = "委托任务（${tasks.size} 个进行中）",
                        cardBody = lines.joinToString("\n"),
                    )
                }
            }
            is com.lingxi.data.delegation.DelegationArgs.Command.Cancel ->
                mutate(command.id, command.title, "取消") { delegation.cancel(it) }
            is com.lingxi.data.delegation.DelegationArgs.Command.Pause ->
                mutate(command.id, command.title, "暂停") { delegation.pause(it) }
            is com.lingxi.data.delegation.DelegationArgs.Command.Resume ->
                mutate(command.id, command.title, "恢复") { delegation.resume(it) }
        }
    }

    private suspend fun mutate(
        id: Long?,
        title: String,
        verb: String,
        action: suspend (Long) -> Boolean,
    ): ActionResult {
        val task = id?.let { delegation.byId(it) } ?: delegation.findActiveByTitle(title)
            ?: return ActionResult.error("找不到任务：${title.ifBlank { "#$id" }}")
        val ok = action(task.id)
        return if (ok) {
            ActionResult.ok("已$verb「${task.title}」", cardTitle = "委托任务已$verb", cardBody = task.title)
        } else {
            ActionResult.error("$verb 失败")
        }
    }

    private fun humanInterval(minutes: Int): String = when {
        minutes % (24 * 60) == 0 -> "${minutes / (24 * 60)}天"
        minutes % 60 == 0 -> "${minutes / 60}小时"
        else -> "$minutes 分钟"
    }

    private fun setAlarm(argsJson: String): ActionResult {
        val args = parseArgs(argsJson)
        val hour = args["hour"]?.toIntOrNull() ?: return ActionResult.error("缺少 hour 参数")
        val minute = args["minute"]?.toIntOrNull() ?: 0
        val label = args["label"]?.takeIf { it.isNotBlank() } ?: "灵犀闹钟"

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            startActivity(intent)
            val timeStr = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
            ActionResult.ok("已设好 $timeStr 的闹钟：$label", cardTitle = "闹钟已设置", cardBody = "$label · $timeStr")
        }.getOrElse {
            ActionResult.error("无法设置闹钟：${it.message ?: "系统拒绝"}")
        }
    }

    private fun openApp(argsJson: String): ActionResult {
        val args = parseArgs(argsJson)
        val raw = args["app_name"]?.trim() ?: return ActionResult.error("缺少 app_name 参数")

        val resolved = runCatching {
            val pm = app.packageManager
            // 精确匹配包名/应用名（LAUNCHER）
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val infos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .filter {
                    val label = it.loadLabel(pm).toString().lowercase()
                    val pkg = it.activityInfo.packageName.lowercase()
                    label.contains(raw.lowercase()) || pkg.contains(raw.lowercase())
                }
            if (infos.isEmpty()) return ActionResult.error("找不到应用：$raw")
            // 精确匹配优先（用户点名"微信"时不要列出一堆含"微信"的）
            val exact = infos.filter { it.loadLabel(pm).toString().lowercase() == raw.lowercase() }
            if (exact.size == 1) return launchAndReport(exact[0])
            val pool = if (exact.size > 1) exact else infos
            if (pool.size == 1) {
                return launchAndReport(pool[0])
            } else {
                val names = pool.map { it.loadLabel(pm).toString() }
                ActionResult.candidates(names)
            }
        }.getOrElse {
            ActionResult.error("打开应用失败：${it.message ?: "未知原因"}")
        }
        return resolved
    }

    /** 启动单个应用并生成结果 */
    private fun launchAndReport(info: android.content.pm.ResolveInfo): ActionResult {
        val pm = app.packageManager
        val launch = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setClassName(info.activityInfo.packageName, info.activityInfo.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            startActivity(launch)
            val name = info.loadLabel(pm).toString()
            ActionResult.ok("已打开 $name", cardTitle = "已打开", cardBody = name)
        }.getOrElse { ActionResult.error("打开失败：${it.message ?: "系统拒绝"}") }
    }

    private fun webSearch(argsJson: String): ActionResult {
        val args = parseArgs(argsJson)
        val query = args["query"]?.trim() ?: return ActionResult.error("缺少 query 参数")
        // 优先系统搜索 intent；失败回落浏览器打开搜索引擎 URL
        val systemSearch = Intent(Intent.ACTION_WEB_SEARCH).apply {
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val result = runCatching { startActivity(systemSearch); true }
            .getOrDefault(false)
        if (!result) {
            val urlIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bing.com/search?q=" + Uri.encode(query))).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val okUrl = runCatching { startActivity(urlIntent); true }.getOrDefault(false)
            if (!okUrl) return ActionResult.error("无法打开搜索")
        }
        return ActionResult.ok("已搜索：$query", cardTitle = "已搜索", cardBody = query)
    }

    private fun sendSms(argsJson: String): ActionResult {
        val args = parseArgs(argsJson)
        val phone = args["phone"]?.trim() ?: return ActionResult.error("缺少 phone 参数")
        val message = args["message"]?.trim() ?: return ActionResult.error("缺少 message 参数")

        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).apply {
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            startActivity(intent)
            ActionResult.ok("短信已准备好，收件人：$phone，内容：$message", cardTitle = "短信已准备", cardBody = "收件人：$phone\n内容：$message")
        }.getOrElse {
            ActionResult.error("无法打开短信：${it.message ?: "系统拒绝"}")
        }
    }

    private fun parseArgs(json: String): Map<String, String> {
        return runCatching {
            val trimmed = json.trim()
            if (!trimmed.startsWith("{")) return emptyMap()
            val obj = Json.parseToJsonElement(trimmed).jsonObject
            obj.entries.associate { it.key to (it.value.jsonPrimitive.contentOrNull ?: "") }
        }.getOrDefault(emptyMap())
    }

    private fun startActivity(intent: Intent) {
        app.startActivity(intent)
    }
}
