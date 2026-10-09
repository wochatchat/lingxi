package com.lingxi.data.memory

import com.lingxi.data.ChatMessage
import com.lingxi.data.LlmEvent
import com.lingxi.data.LlmStream
import com.lingxi.data.ProviderRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F7 三层记忆实现：
 * - 会话层：每轮落 Room，重启恢复滑窗
 * - 中期层：跨天时把上一天未摘要对话 LLM 压缩成每日摘要（滚动保留 [SUMMARY_KEEP_DAYS] 天）
 * - 长期层：语音编辑门控命中 → LLM 提取一条画像，下一轮 system prompt 即时生效
 * LLM 维护操作在自有 scope 异步跑，失败静默（记忆是增强，不阻塞对话主链路）。
 */
@Singleton
class MemoryRepository @Inject constructor(
    private val dao: MemoryDao,
    private val llm: LlmStream,
    private val providers: ProviderRepository,
) : MemoryStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 本次进程内已做过的日滚检查（跨天才真正触发摘要） */
    @Volatile private var checkedDay: String? = null

    override suspend fun loadRecentTurns(limit: Int): List<StoredTurn> =
        withContext(Dispatchers.IO) {
            dao.recentTurnsRaw(limit).reversed().map { StoredTurn(it.user, it.reply) }
        }

    override suspend fun profileLines(): List<String> = withContext(Dispatchers.IO) {
        dao.enabledProfileContents()
    }

    override suspend fun recentDailySummaries(limit: Int): List<DailySummary> =
        withContext(Dispatchers.IO) {
            dao.recentSummaries(limit).map { DailySummary(it.date, it.summary) }
        }

    override suspend fun onTurnCompleted(user: String, reply: String) {
        withContext(Dispatchers.IO) { dao.insertTurn(TurnEntity(createdAt = System.currentTimeMillis(), user = user, reply = reply)) }
        val today = todayString()
        if (checkedDay == today) return
        checkedDay = today
        scope.launch { extractProfile(user) }
        scope.launch { rollDailySummary(today) }
    }

    // ---- 长期画像：语音编辑记忆 ----

    private suspend fun extractProfile(userText: String) {
        if (!ProfileGate.shouldExtract(userText)) return
        val (config, key) = providers.firstEnabled() ?: return
        val reply = collectReply(
            llm.streamChat(
                apiKey = key,
                baseUrl = config.baseUrl,
                model = config.model,
                messages = listOf(
                    ChatMessage("system", EXTRACT_SYSTEM_PROMPT),
                    ChatMessage("user", userText),
                ),
                temperature = 0.0,
            )
        ) ?: return
        val content = parseProfileExtraction(reply) ?: return
        // 去重：完全相同内容不重复沉淀
        if (withContext(Dispatchers.IO) { dao.allProfileContents() }.any { it == content }) return
        withContext(Dispatchers.IO) {
            dao.insertProfile(ProfileEntryEntity(content = content, source = "voice_edit", createdAt = System.currentTimeMillis()))
        }
    }

    // ---- 中期层：每日滚动摘要 ----

    /** 跨天时：把今天 0 点之前的未摘要对话按日期分组，逐日摘要落库（一次最多处理 3 天，控制成本） */
    private suspend fun rollDailySummary(today: String) {
        val startOfToday = dayStartMs(today)
        val pending = withContext(Dispatchers.IO) { dao.pendingTurns(startOfToday, PENDING_LIMIT) }
        if (pending.isEmpty()) return
        val byDate = pending.groupBy { dateStringOf(it.createdAt) }
        val datesToProcess = byDate.keys.sorted().takeLast(MAX_PENDING_DAYS)
        for (date in datesToProcess) {
            summarizeOneDay(date, byDate[date].orEmpty()) ?: continue
        }
        withContext(Dispatchers.IO) { dao.pruneSummaries(SUMMARY_KEEP_DAYS) }
    }

    private suspend fun summarizeOneDay(date: String, turns: List<TurnEntity>): String? {
        val (config, key) = providers.firstEnabled() ?: return null
        val transcript = turns.joinToString("\n") { "用户: ${it.user}\n灵犀: ${it.reply}" }
        val reply = collectReply(
            llm.streamChat(
                apiKey = key,
                baseUrl = config.baseUrl,
                model = config.model,
                messages = listOf(
                    ChatMessage("system", SUMMARY_SYSTEM_PROMPT),
                    ChatMessage("user", transcript.take(4000)),
                ),
                temperature = 0.2,
            )
        ) ?: return null
        val summary = parseDailySummary(reply)
        if (summary.isBlank()) return null
        withContext(Dispatchers.IO) {
            dao.upsertSummary(DailySummaryEntity(date = date, summary = summary, updatedAt = System.currentTimeMillis()))
            dao.markSummarized(turns.map { it.id })
        }
        return summary
    }

    // ---- 管理接口（记忆管理页用） ----

    val profileEntries = dao.profileEntries()

    suspend fun setProfileEnabled(id: Long, enabled: Boolean) = dao.setProfileEnabled(id, enabled)
    suspend fun deleteProfile(id: Long) = dao.deleteProfile(id)
    suspend fun clearProfile() = dao.clearProfile()
    suspend fun clearTurns() = dao.clearTurns()
    fun summariesFlow(limit: Int = 30): Flow<List<DailySummary>> =
        dao.summariesFlow(limit).map { list -> list.map { DailySummary(it.date, it.summary) } }

    // ---- 工具 ----

    private suspend fun collectReply(flow: kotlinx.coroutines.flow.Flow<LlmEvent>): String? {
        val sb = StringBuilder()
        var failed = false
        flow.collect { ev ->
            when (ev) {
                is LlmEvent.Delta -> sb.append(ev.text)
                is LlmEvent.Failed -> failed = true
                is LlmEvent.Completed -> Unit
            }
        }
        return if (failed) null else sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val SUMMARY_KEEP_DAYS = 14
        private const val PENDING_LIMIT = 400
        private const val MAX_PENDING_DAYS = 3

        const val EXTRACT_SYSTEM_PROMPT =
            "你是记忆提取器。从用户对语音助手说的话里，提取一条应当长期记住的关于用户的事实、偏好或称呼指令，" +
                "改写成一句简洁的第三人称陈述（如「用户希望回答结论先行」「用户名叫阿哲」）。" +
                "只输出这一句话本身；如果这句话没有值得长期记住的内容，只输出 NONE。"

        const val SUMMARY_SYSTEM_PROMPT =
            "你是对话摘要器。把用户与语音助手「灵犀」的对话压缩成 3~5 条要点，" +
                "每条一行以「-」开头，只保留对后续对话有用的信息（用户提到的事实、决定、待办、偏好），" +
                "不要输出任何其他内容。"

        fun todayString(): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(System.currentTimeMillis())

        fun dateStringOf(epochMs: Long): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(epochMs)

        fun dayStartMs(date: String): Long {
            val cal = Calendar.getInstance()
            val parts = date.split("-").map { it.toInt() }
            cal.set(parts[0], parts[1] - 1, parts[2], 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}
