package com.lingxi.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Stream-UI 原语数据模型（PRD §11.2 七原语）：
 * - ChoiceSheet：点选列表（半模态，语音等价"第 N 个"）
 * - ConfirmGate：确认门（半/全模态，高危动作双确认）
 * - InfoCard：信息卡（非打断，点按展开）
 * - ParamPanel：参数表单（委托任务配置等，可直接口述参数）
 * - ProgressBar：进度条（下载/上传/巡查类委托，非打断）
 * - MiniChart：小图表（委托历史趋势，非打断）
 * - TakeoverPrompt：接管请求（全模态限时，超时自动降级为草稿）
 *
 * UiAction 挂靠在对话轮次上，历史卡片可回放（PRD §11.1）。
 */
data class UiAction(
    val id: Long = System.currentTimeMillis(),
    val type: UiType,
    val title: String,
    val body: String? = null,
    /** ChoiceSheet 选项列表 */
    val options: List<String> = emptyList(),
    /** ConfirmGate 的 extra 说明 */
    val confirmLabel: String = "确认",
    val cancelLabel: String = "取消",
    /** TTL（毫秒），超时自动取消（兜底，防止 UI 卡死） */
    val ttlMs: Long = 60_000,
    /** 已选中的选项（解析 voice choice 后填充） */
    val resolvedIndex: Int? = null,
    val resolvedText: String? = null,
    /** ParamPanel 参数字段（R9） */
    val fields: List<ParamField> = emptyList(),
    /** ProgressBar 进度 0-100；-1 = 不确定态（R9） */
    val progress: Int = -1,
    /** MiniChart 数值序列（R9） */
    val values: List<Float> = emptyList(),
) {
    /** ParamPanel 单个参数字段 */
    data class ParamField(
        val key: String,
        val label: String,
        val value: String,
        val hint: String = "",
    )

    enum class UiType {
        ChoiceSheet, ConfirmGate, InfoCard,
        ParamPanel, ProgressBar, MiniChart, TakeoverPrompt,
    }

    companion object {
        /** 从用户文本解析 voice choice："第 2 个"、"第二个"、"选第一个" → 0-based index */
        fun parseVoiceChoice(text: String): Int? {
            val t = text.trim().replace(" ", "").replace("\u3000", "") // 空格折叠（"第 2 个"）
            if (t.isEmpty() || t.length > 4) return null // 长句直接排除，防误命中（如"统一意见"）
            // "第2个" / "第 2 个" / "第二个" / "第二" / "二"
            val numWords = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
            val numPattern = Regex("""(?:第|选)?\s*([1-9]\d*|[\u4e00-\u9fa5])\s*(?:个|个?选项)?$""")
            numPattern.find(t)?.let { m ->
                val raw = m.groupValues[1]
                val idx = when {
                    raw.first().isDigit() -> raw.toIntOrNull()?.minus(1)
                    raw.length == 1 -> {
                        val pos = numWords.indexOf(raw)
                        if (pos >= 0) pos else null
                    }
                    else -> null
                }
                return idx
            }
            // "三" alone
            val pos = numWords.indexOf(t)
            if (pos >= 0) return pos
            return null
        }

        /** 从用户文本解析 confirm/deny："确认"/"好"/"是的"/"执行"/"取消"/"算了" */
        fun parseConfirm(text: String): Boolean? {
            val t = text.trim()
            return when (t) {
                in listOf("确认", "好", "是的", "执行", "行", "可以", "确定", "是", "y", "yes") -> true
                in listOf("取消", "算了", "不", "不要", "否", "n", "no") -> false
                else -> null
            }
        }

        fun isExpired(action: UiAction, createdAt: Long): Boolean =
            System.currentTimeMillis() - createdAt > action.ttlMs

        /** 生成 ChoiceSheet 的语音提示文本 */
        fun voicePrompt(action: UiAction): String {
            if (action.type != UiType.ChoiceSheet) return ""
            val opts = action.options.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("，")
            return "请选择一个：$opts。说第 N 个选择。"
        }

        /** 生成 ConfirmGate/TakeoverPrompt 的语音提示文本 */
        fun confirmVoicePrompt(action: UiAction): String {
            if (action.type != UiType.ConfirmGate && action.type != UiType.TakeoverPrompt) return ""
            val question = action.body ?: "确定执行吗？"
            return "$question 说\"${action.confirmLabel}\"或\"${action.cancelLabel}\"。"
        }

        /** ParamPanel 默认值 → JSON（{"key":"value"}，引擎回读用） */
        fun paramDefaultJson(action: UiAction): String {
            val obj = action.fields.associate { it.key to JsonPrimitive(it.value) }
            return kotlinx.serialization.json.JsonObject(obj).toString()
        }

        /** ParamPanel 应答摘要（"间隔：每小时；通知：通知栏"），answers 缺省字段回落默认值 */
        fun paramSummary(action: UiAction, answers: Map<String, String>): String =
            action.fields.joinToString("；") { f ->
                "${f.label}：${answers[f.key]?.takeIf { v -> v.isNotBlank() } ?: f.value}"
            }
    }
}
