package com.lingxi.data.inspection

/**
 * F14 LLM Agent 主动巡检（PRD §3.4；R12 AgentLoop 落地）。
 *
 * 实现：LlmInspectionAgent（data/agent/）——LLM 多步工具循环 + 自检总结。
 * 触发面：周期巡检（InspectionWorker，默认每日）+ 委托 Final 事件即时触发；
 * 结果走主动通知 + 对话卡片落史；无值得播报的变化返回 null（不打扰）。
 */
interface InspectionAgent {
    /**
     * 执行一次巡检；返回摘要文本，null = 没有值得播报的变化。
     * @param reason 触发原因（如「委托 XX 完结」）；null = 例行巡检
     */
    suspend fun inspect(reason: String? = null): String?
}
