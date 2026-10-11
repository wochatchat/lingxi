package com.lingxi.data.memory

/**
 * R13 行为预测纯逻辑：时段(2h 桶) × 星期 × 行为 的本地统计打分。
 * 全部无副作用，单测覆盖；数据不上云，防「未卜先知式骚扰」的门槛全部内置：
 * 最小观测次数 + 置信度阈值 + 忽略降权 + 上限条数（总开关在设置层，默认关）。
 */
object BehaviorPredictor {

    /** 时段桶粒度：2 小时一桶，一天 12 桶 */
    const val BUCKET_HOURS = 2

    /** 单动作至少观测 N 次才参与预测（防一两次巧合就弹建议） */
    const val MIN_OBSERVATIONS = 3

    /** 置信度阈值：得分 / 最高分 低于此值不推荐 */
    const val MIN_CONFIDENCE = 0.6

    /** 一次忽略等价于扣减的计数（静默降权） */
    const val IGNORE_PENALTY = 1.5

    const val MAX_SUGGESTIONS = 3

    /** 单条建议通知的置信度门槛（高于 chips 阈值，防通知骚扰） */
    const val NOTIFY_CONFIDENCE = 0.85

    /** 时段桶：hour / 2 → 0..11 */
    fun hourBucket(epochMs: Long): Int = (calendarOf(epochMs)[java.util.Calendar.HOUR_OF_DAY]) / BUCKET_HOURS

    /** 星期（周一=1 … 周日=7） */
    fun weekday(epochMs: Long): Int {
        val dow = calendarOf(epochMs).get(java.util.Calendar.DAY_OF_WEEK) // SUNDAY=1..SATURDAY=7
        return ((dow + 5) % 7) + 1
    }

    /**
     * 由统计行算当前时刻的建议列表（纯函数）。
     * 打分：同桶同星期权重 1.0，同桶跨星期 0.5（星期弱相关）；
     * 有效计数 = count - IGNORE_PENALTY × ignoredCount，被忽略多的建议自然沉底。
     */
    fun suggest(rows: List<BehaviorRow>, nowMs: Long): List<Suggestion> {
        val bucket = hourBucket(nowMs)
        val weekdayNow = weekday(nowMs)
        val scores = LinkedHashMap<String, Double>()
        for (r in rows) {
            if (r.hourBucket != bucket || r.count < MIN_OBSERVATIONS) continue
            val effective = r.count - IGNORE_PENALTY * r.ignoredCount
            if (effective <= 0) continue
            val w = if (r.weekday == weekdayNow) 1.0 else 0.5
            scores[r.action] = (scores[r.action] ?: 0.0) + effective * w
        }
        if (scores.isEmpty()) return emptyList()
        val max = scores.values.max()
        return scores.entries
            .filter { it.value / max >= MIN_CONFIDENCE }
            .sortedByDescending { it.value }
            .take(MAX_SUGGESTIONS)
            .mapNotNull { (action, score) ->
                val catalog = catalog(action) ?: return@mapNotNull null
                Suggestion(action, score / max, catalog.first, catalog.second)
            }
    }

    private fun calendarOf(epochMs: Long): java.util.Calendar =
        java.util.Calendar.getInstance().apply { timeInMillis = epochMs }

    /** DIRECT 意图工具名 → 统计行为 id（未知工具统一记 chat） */
    fun actionForTool(toolName: String): String = when {
        toolName.contains("alarm") -> "alarm"
        toolName.contains("app") -> "app"
        toolName.contains("search") -> "search"
        toolName.contains("manage_task") -> "delegation"
        else -> "chat"
    }

    /** 建议目录：行为 id → (chip 文案, 点选执行的口令)；无目录的行为不生成 chips */
    fun catalog(action: String): Pair<String, String>? = when (action) {
        "chat" -> "聊聊天" to "陪我聊会儿天"
        "alarm" -> "定个闹钟" to "帮我定一个闹钟"
        "search" -> "搜一下" to "帮我搜一下今天的新闻"
        "delegation" -> "看委托" to "看看我的委托任务进展"
        "morning_report" -> "来份晨报" to "给我来一份晨报"
        else -> null
    }
}

/** 预测出的主动建议（主页 chips / 可选通知共用） */
data class Suggestion(
    val action: String,
    val confidence: Double,
    val label: String,
    val query: String,
)

/** DAO 投影：行为统计行（打分输入） */
data class BehaviorRow(
    val hourBucket: Int,
    val weekday: Int,
    val action: String,
    val count: Int,
    val ignoredCount: Int,
)
