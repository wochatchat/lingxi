package com.lingxi.data.functions

import kotlinx.serialization.Serializable

/**
 * F8 Function Calling 工具定义（OpenAI tool_call 协议）。
 * 四个基础动作（PRD §3.3）：设闹钟/提醒、打开 App、网页搜索、发送短信。
 */
object ToolDefs {

    val ALL = listOf(
        ToolSpec(
            name = "set_alarm",
            description = "设置一个闹钟。可用于「设闹钟」「提醒我」等场景。参数为时（0-23）和分（0-59），以及可选的标签（闹钟显示的文字）。",
            parametersJson = """
                {
                  "type": "object",
                  "properties": {
                    "hour": { "type": "integer", "description": "小时，0-23" },
                    "minute": { "type": "integer", "description": "分钟，0-59，默认为 0" },
                    "label": { "type": "string", "description": "闹钟标签/备注，描述这个闹钟是什么事" }
                  },
                  "required": ["hour"]
                }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "open_app",
            description = "打开设备上已安装的应用。如果找不到对应的应用，返回搜索建议。参数为应用名称（中文或英文均可）。",
            parametersJson = """
                {
                  "type": "object",
                  "properties": {
                    "app_name": { "type": "string", "description": "要打开的应用名称，如「微信」「抖音」「小红书」「设置」" }
                  },
                  "required": ["app_name"]
                }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "web_search",
            description = "使用系统默认浏览器搜索网页内容。参数为搜索关键词。",
            parametersJson = """
                {
                  "type": "object",
                  "properties": {
                    "query": { "type": "string", "description": "搜索关键词，应简洁准确" }
                  },
                  "required": ["query"]
                }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "send_sms",
            description = "打开短信应用并填充收件人和正文，用户手动点击发送（安全边界：灵犀不自动发送短信）。",
            parametersJson = """
                {
                  "type": "object",
                  "properties": {
                    "phone": { "type": "string", "description": "收件人手机号（11 位数字）" },
                    "message": { "type": "string", "description": "短信正文内容" }
                  },
                  "required": ["phone", "message"]
                }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "ui_action",
            description = "向用户呈现 Stream-UI 交互指令（ChoiceSheet / ConfirmGate / InfoCard）。当需要用户选择、确认或展示信息时调用此工具。",
            parametersJson = """
                {
                  "type": "object",
                  "properties": {
                    "type": { "type": "string", "enum": ["ChoiceSheet", "ConfirmGate", "InfoCard", "ParamPanel", "ProgressBar", "MiniChart", "TakeoverPrompt"], "description": "交互类型" },
                    "title": { "type": "string", "description": "卡片/列表标题" },
                    "body": { "type": "string", "description": "补充说明文字（ConfirmGate 的确认问题 / InfoCard 的正文）" },
                    "options": { "type": "array", "items": { "type": "string" }, "description": "ChoiceSheet 选项列表（每个选项一行）" },
                    "fields": { "type": "array", "description": "ParamPanel 参数字段", "items": { "type": "object", "properties": { "key": { "type": "string" }, "label": { "type": "string", "description": "字段中文名" }, "value": { "type": "string", "description": "默认值" }, "hint": { "type": "string" } }, "required": ["key", "label"] } },
                    "progress": { "type": "integer", "description": "ProgressBar 进度 0-100，-1 为不确定态" },
                    "values": { "type": "array", "items": { "type": "number" }, "description": "MiniChart 数值序列" },
                    "confirm_label": { "type": "string", "description": "确认按钮文字，默认为「确认」" },
                    "cancel_label": { "type": "string", "description": "取消按钮文字，默认为「取消」" },
                    "ttl_ms": { "type": "integer", "description": "超时毫秒数，默认为 60000（60 秒）；TakeoverPrompt 建议设 10000，超时自动转草稿不执行" }
                  },
                  "required": ["type", "title"]
                }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "manage_task",
            description = "管理委托任务（后台跟踪）：创建一次性提醒、创建周期巡查任务、查询/暂停/恢复/取消任务。「盯着 XX 到了告诉我」「每天下午 3 点提醒我打坐」「查一下我的委托任务」等场景使用。",
            parametersJson = """
                {
                  "type": "object",
                  "properties": {
                    "action": { "type": "string", "enum": ["create", "list", "cancel", "pause", "resume"], "description": "操作类型" },
                    "kind": { "type": "string", "enum": ["reminder", "poll"], "description": "create 时：reminder=一次性到点提醒，poll=周期巡查（如盯快递、每天提醒）" },
                    "title": { "type": "string", "description": "任务标题，如「盯 XX 快递签收」「打坐」" },
                    "when_text": { "type": "string", "description": "reminder 的时间描述，如「明天 8:30」「2小时后」；poll 的「每天 15 点」也可放这里" },
                    "interval_minutes": { "type": "integer", "description": "poll 的巡查间隔分钟数（最小 15），如每小时=60" },
                    "tracking_no": { "type": "string", "description": "盯快递时：快递单号（8-15位）。提供后任务会用真实快递跟踪（需在设置里配置快递100 查询凭据）" },
                    "company": { "type": "string", "description": "盯快递时：快递公司（如 顺丰/中通/ems），可省略自动识别" },
                    "id": { "type": "integer", "description": "cancel/pause/resume 时：任务 id（list 可见）；无 id 时按 title 模糊匹配" }
                  },
                  "required": ["action"]
                }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "read_screen",
            description = "读取当前屏幕上可见的文字内容（无障碍能力，需用户开启）。用于「这个页面写了什么」「帮我看看屏幕」等场景。无参数。",
            parametersJson = """
                {
                  "type": "object",
                  "properties": {},
                  "required": []
                }
            """.trimIndent(),
        ),
        ToolSpec(
            name = "click_ui",
            description = "点击当前屏幕上包含指定文字的可点击元素（如按钮、链接）。灵犀会在执行前向用户确认。参数 target 为要点击的元素文字。",
            parametersJson = """
                {
                  "type": "object",
                  "properties": {
                    "target": { "type": "string", "description": "要点击的元素上的文字，如「同意」「发送」" }
                  },
                  "required": ["target"]
                }
            """.trimIndent(),
        ),
    )

    fun byName(name: String): ToolSpec? = ALL.find { it.name == name }
}

@Serializable
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String,
)
