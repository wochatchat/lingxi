package com.lingxi.data.inspection

/**
 * F14 LLM Agent 主动巡检（PRD §3.4：v2 功能，v1 预留接口）。
 *
 * 设计意图：周期性把外部数据源（新闻/邮箱/股价等，v2 接入）交给 LLM 聚合成
 * 「值得打扰用户的变化」，产出一句话摘要；无变化返回 null（不打扰）。
 * v2 接入路径：实现本接口 → WorkerEntryPoint 暴露 → 周期 worker（仿 PollWorker）调用。
 */
interface InspectionAgent {
    /** 执行一次巡检；返回摘要文本，null = 没有值得播报的变化 */
    suspend fun inspect(): String?
}
