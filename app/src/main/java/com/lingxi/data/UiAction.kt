package com.lingxi.data

import kotlinx.coroutines.withTimeoutOrNull

/**
 * Stream-UI 三原语数据模型（PRD §11.2）：
 * - ChoiceSheet：点选列表（半模态，语音等价"第 N 个"）
 * - ConfirmGate：确认门（半/全模态，高危动作双确认）
 * - InfoCard：信息卡（非打断，点按展开）
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
) {
    enum class UiType {
        ChoiceSheet, ConfirmGate, InfoCard
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

        /** 生成 ConfirmGate 的语音提示文本 */
        fun confirmVoicePrompt(action: UiAction): String {
            if (action.type != UiType.ConfirmGate) return ""
            val question = action.body ?: "确定执行吗？"
            return "$question 说\"${action.confirmLabel}\"或\"${action.cancelLabel}\"。"
        }
    }
}
