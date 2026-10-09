package com.lingxi.data.functions

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 系统动作执行器（F8 四个工具的 Android 实现）：
 * - set_alarm    → AlarmClock.ACTION_SET_ALARM（SKIP_UI=true，不弹设置界面）
 * - open_app     → PackageManager query → launch
 * - web_search   → AlarmClock.ACTION_WEB_SEARCH（系统默认搜索）
 * - send_sms     → Intent.ACTION_SENDTO（smsto: 预填收件人+正文）
 */
@Singleton
class AndroidActionExecutor @Inject constructor(
    @ApplicationContext private val app: Context,
) : ActionExecutor {

    override suspend fun execute(toolName: String, argsJson: String): ActionResult {
        return when (toolName) {
            "set_alarm" -> setAlarm(argsJson)
            "open_app" -> openApp(argsJson)
            "web_search" -> webSearch(argsJson)
            "send_sms" -> sendSms(argsJson)
            else -> ActionResult.error("未知工具: $toolName")
        }
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
