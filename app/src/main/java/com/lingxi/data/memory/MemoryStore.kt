package com.lingxi.data.memory

/**
 * F7 三层记忆的存储门面（供 ConversationEngine 依赖的窄接口）。
 * 真实实现 [MemoryRepository] 内部做 LLM 提取/摘要；测试用 fake 替换。
 */
interface MemoryStore {

    /** 重启恢复会话滑窗：最近 N 轮（时间升序） */
    suspend fun loadRecentTurns(limit: Int): List<StoredTurn>

    /** 生效中的长期画像条目（拼进 system prompt，即时生效） */
    suspend fun profileLines(): List<String>

    /** 最近 N 天每日摘要（拼进 system prompt） */
    suspend fun recentDailySummaries(limit: Int): List<DailySummary>

    /** 一轮对话成功完成：落库 + 触发记忆维护（画像提取 / 日滚摘要，内部异步） */
    suspend fun onTurnCompleted(user: String, reply: String)

    /** R13 行为统计：一次可观测行为发生（chat/alarm/search/delegation/morning_report…），本地计数 */
    suspend fun onBehaviorObserved(action: String, atMs: Long) {}

    /** R13 兴趣画像：权重最高的 N 个兴趣话题（拼进 system prompt） */
    suspend fun topInterests(limit: Int): List<String> = emptyList()
}

data class StoredTurn(val user: String, val reply: String)

data class DailySummary(val date: String, val summary: String)
