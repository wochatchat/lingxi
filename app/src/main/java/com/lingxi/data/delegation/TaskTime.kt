package com.lingxi.data.delegation

/**
 * 委托任务时间解析（纯函数，可单测）。
 * 支持：
 * - 「每天/每日 + X点(半/N分)」→ 每日循环
 * - 「每小时 / 每N分钟 / 每N小时」→ 周期间隔
 * - 「N分钟后 / N小时后」→ 相对时刻
 * - 「(今天|明天|后天)?(凌晨|早上|上午|中午|下午|傍晚|晚上)?X点(半|N分?)?」与「HH:mm」→ 绝对时刻
 */
object TaskTime {

    /** 解析结果 */
    sealed interface When {
        /** 绝对时刻（epoch ms） */
        data class At(val epochMillis: Long, val human: String) : When

        /** 每日固定时刻 */
        data class Daily(val hour: Int, val minute: Int) : When

        /** 周期间隔（分钟） */
        data class Every(val intervalMinutes: Int) : When
    }

    fun parse(text: String, nowMillis: Long, zone: java.time.ZoneId): When? {
        val t = text.trim().replace('：', ':')
        if (t.isEmpty()) return null

        // ① 每天/每日 → Daily
        if (Regex("""每(天|日)""").containsMatchIn(t)) {
            val hm = parseHourMinute(t) ?: return null
            return When.Daily(hm.first, hm.second)
        }

        // ② 每 N 分钟 / 每 N 小时 / 每小时
        Regex("""每\s*(\d{1,3})\s*(分钟|分|小时|钟头)""").find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@let
            if (n <= 0) return@let
            val unit = m.groupValues[2]
            return When.Every(if (unit.contains("小时") || unit.contains("钟头")) n * 60 else n)
        }
        if (Regex("""每小时""").containsMatchIn(t)) return When.Every(60)

        // ③ N 分钟/小时 后 → 相对时刻
        Regex("""(\d{1,4})\s*(分钟|分|小时|钟头)后""").find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@let
            if (n <= 0) return@let
            val deltaMin = if (m.groupValues[2].contains("小时") || m.groupValues[2].contains("钟头")) n * 60L else n.toLong()
            val at = nowMillis + deltaMin * 60_000L
            return When.At(at, formatAt(at, zone))
        }

        // ④ 绝对时刻（可带 今天/明天/后天 与时段词）
        val dayOffset = when {
            t.contains("明天") -> 1
            t.contains("后天") -> 2
            else -> 0
        }
        val hm = parseHourMinute(t) ?: return null
        var at = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone)
            .toLocalDate().plusDays(dayOffset.toLong())
            .atTime(hm.first, hm.second)
            .atZone(zone).toInstant().toEpochMilli()
        // 未指定日期且时刻已过 → 明天
        if (dayOffset == 0 && at <= nowMillis) {
            at += 24 * 60 * 60_000L
        }
        return When.At(at, formatAt(at, zone))
    }

    /** 提取「X点(半|N分?)?」或「X:NN」，含上午/下午归一化 */
    private fun parseHourMinute(t: String): Pair<Int, Int>? {
        // 冒号格式
        Regex("""(\d{1,2})\s*:\s*(\d{1,2})""").find(t)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return@let
            val min = m.groupValues[2].toIntOrNull() ?: return@let
            if (h in 0..23 && min in 0..59) return normalize(h, min, t)
        }
        // 点分格式
        Regex("""(\d{1,2})\s*点\s*(半|(\d{1,2})\s*分?)?""").find(t)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return@let
            val min = when {
                m.groupValues[3].isNotBlank() -> m.groupValues[3].toIntOrNull() ?: return@let
                m.groupValues[2] == "半" -> 30
                else -> 0
            }
            if (h in 0..23 && min in 0..59) return normalize(h, min, t)
        }
        return null
    }

    private fun normalize(hour: Int, minute: Int, text: String): Pair<Int, Int> {
        val h = if (hour < 12 && (text.contains("下午") || text.contains("晚上") || text.contains("傍晚"))) {
            hour + 12
        } else {
            hour
        }
        return (h % 24) to minute
    }

    /** epoch ms → 「明天 08:30」人类可读（仅用于口播/卡片） */
    fun formatAt(epochMillis: Long, zone: java.time.ZoneId): String {
        val z = java.time.Instant.ofEpochMilli(epochMillis).atZone(zone)
        val nowDate = java.time.LocalDate.now(zone)
        val day = when (z.toLocalDate()) {
            nowDate -> "今天"
            nowDate.plusDays(1) -> "明天"
            nowDate.plusDays(2) -> "后天"
            else -> "${z.monthValue}月${z.dayOfMonth}日"
        }
        return "$day ${"%02d:%02d".format(z.hour, z.minute)}"
    }
}
