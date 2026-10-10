package com.lingxi.data

/**
 * F5 意图分类路由器（轻量规则版）：
 * - DIRECT：命中确定性规则，无需 LLM，直接执行系统动作（零 token 成本）
 * - TOOLS_LLM：匹配可能需要工具的任务，交 LLM + tool_call
 * - CHAT：闲聊/普通问答，走正常 LLM 流
 *
 * 模型档位路由（本地 1.5B / 云端主力 / 多模态）待 R7+ 实现。
 */
object IntentRouter {

    /** 路由结果 */
    sealed interface Route {
        /** 直接执行系统动作（toolName + JSON 参数） */
        data class Direct(val toolName: String, val args: Map<String, String>) : Route

        /** 交 LLM 并携带 tools 参数（模型自行决定是否 tool_call） */
        data object ToolsLlm : Route

        /** 闲聊/普通问答，无 tools */
        data object Chat : Route

        /** F10 截屏问答：读取最近截图 + 多模态上行（需确认门） */
        data object Screenshot : Route
    }

    /**
     * 解析 DIRECT args 中的时间参数，返回 (hour, minute) 或 null。
     * 支持：「7点」「7:30」「早上7点」「明天早上7点半」「下午3点」
     */
    fun extractAlarmTime(text: String): Pair<Int, Int>? {
        val t = text.lowercase()
        // ① 冒号格式：7:30 / 7：30
        Regex("""(\d{1,2})\s*[:：]\s*(\d{2})""").find(t)?.let { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: return@let
            val minute = m.groupValues[2].toIntOrNull() ?: 0
            return normalizeAlarm(hour, minute, t)
        }
        // ② 点分格式：7点 / 7点30 / 7点半 / 7点30分
        Regex("""(\d{1,2})\s*点\s*(半|(\d{1,2})\s*分?)?""").find(t)?.let { m ->
            val hour = m.groupValues[1].toIntOrNull() ?: return@let
            val minute = when {
                m.groupValues[3].isNotBlank() -> m.groupValues[3].toIntOrNull() ?: 0
                m.groupValues[2] == "半" -> 30
                else -> 0
            }
            return normalizeAlarm(hour, minute, t)
        }
        return null
    }

    private fun normalizeAlarm(hour: Int, minute: Int, text: String): Pair<Int, Int> {
        val adjusted = if (hour < 12 && (text.contains("下午") || text.contains("晚上") || text.contains("傍晚"))) hour + 12
        else if (hour < 12 && (text.contains("早上") || text.contains("上午") || text.contains("凌晨"))) hour
        else hour
        return (adjusted % 24) to minute
    }

    fun route(text: String): Route {
        val t = text.trim()
        val tNoPunct = t.replace(Regex("""[？?。.!！]"""), "").trim()

        // F10 截屏问答（优先于其他路由）：「看看屏幕上这个」「截屏看看」等
        val screenshotHint = Regex("""截屏|截个屏|截图|屏幕上|看看屏幕|看一下屏幕|看下屏幕|读一下屏幕|读屏|当前屏幕""")
        if (screenshotHint.containsMatchIn(tNoPunct)) return Route.Screenshot

        // F13 委托任务意图优先于 set_alarm：周期性提醒/后台跟踪交 LLM tools（manage_task）
        val delegateHint = Regex("""每(天|日|周|小时)|盯|跟踪|到货|签收|到了告诉我|快递|航班|监控""")
        if (delegateHint.containsMatchIn(tNoPunct)) return Route.ToolsLlm

        // 设闹钟/提醒：祈使句开头（设/定/来/加/响/上/叫我/提醒…）且句中含闹钟|闹铃|提醒
        val imperative = Regex("""^(请|帮我|麻烦|麻烦你)?(设|定|来|加|上|响|提醒|叫我)""")
        val alarmNoun = Regex("""闹钟|闹铃|提醒""")
        if (imperative.containsMatchIn(t) && alarmNoun.containsMatchIn(t)) {
            val hourMin = extractAlarmTime(t)
            val args = buildMap {
                hourMin?.let { put("hour", it.first.toString()); put("minute", it.second.toString()) }
                // 尝试提取 label：从"设7点开会"→"开会"（去掉开头的"的"）
                Regex("""\d{1,2}\s*点\s*(.+)""").find(t)?.groupValues?.getOrNull(1)?.trim()?.let {
                    val label = it.removePrefix("的").trim()
                    if (label.isNotBlank() && label.length < 50 && !label.contains(Regex("""闹钟|闹铃"""))) put("label", label)
                }
            }
            return Route.Direct("set_alarm", args)
        }
        // 打开应用
        val openKw = Regex("""^(打开|启动|运行|开一下|开启)\s*(.+)""", RegexOption.IGNORE_CASE)
        openKw.find(t)?.let { m ->
            val app = m.groupValues[2].trim()
            if (app.isNotBlank()) return Route.Direct("open_app", mapOf("app_name" to app))
        }
        // 网页搜索
        val searchKw = Regex("""^(搜索|搜一下|搜一搜|查一下|查询|帮我查|帮我搜)\s*(.+)""", RegexOption.IGNORE_CASE)
        if (searchKw.containsMatchIn(t)) {
            searchKw.find(t)?.let { m ->
                val query = m.groupValues[2].trim()
                if (query.isNotBlank()) return Route.Direct("web_search", mapOf("query" to query))
            }
        }
        // 发送短信（明确说"发短信给X"/"发条短信"）
        val smsKw = Regex("""^(发|发送|发条)?\s*短信\s*(给|至|to)?\s*(\d{11})""", RegexOption.IGNORE_CASE)
        if (smsKw.containsMatchIn(t) && t.contains(Regex("""\d{11}"""))) {
            val phoneMatch = Regex("""\d{11}""").find(t)?.value ?: ""
            val msg = t.replace(Regex("""[\d]{11}"""), "").replace(Regex("""(发|发送|发条短信|给|至)"""), "").trim()
            if (phoneMatch.isNotBlank()) return Route.Direct("send_sms", mapOf("phone" to phoneMatch, "message" to msg))
        }

        // 动作类但规则没命中（说法千变万化）→ LLM tools（模型自行决定 tool_call）
        val actionHint = Regex("""闹钟|提醒|闹铃|打开|启动|搜索|搜一下|查一下|帮我查|发短信|打电话|倒计时""")
        if (actionHint.containsMatchIn(tNoPunct)) return Route.ToolsLlm

        // 纯闲聊/普通问答：不带 tools（省 token，F5 的成本路由基线）
        return Route.Chat
    }
}
