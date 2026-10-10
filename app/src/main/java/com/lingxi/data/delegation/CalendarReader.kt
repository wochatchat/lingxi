package com.lingxi.data.delegation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 日历事件（纯数据，便于单测过滤逻辑） */
data class CalendarEvent(
    val eventId: Long,
    val title: String,
    /** 开始时间 epoch ms */
    val begin: Long,
    /** 结束时间 epoch ms */
    val end: Long,
    val location: String = "",
) {
    /** 晨报一行：「14:00 需求评审（会议室A）」 */
    fun display(): String {
        val z = Instant.ofEpochMilli(begin).atZone(ZoneId.systemDefault())
        val hm = "%02d:%02d".format(z.hour, z.minute)
        return buildString {
            append(hm).append(' ').append(title)
            if (location.isNotBlank()) append("（").append(location).append('）')
        }
    }

    /** 提醒通知正文：还有多久开始 */
    fun describe(leadMinutes: Int): String {
        val remainMin = (begin - System.currentTimeMillis()) / 60_000L
        val whenText = if (remainMin > leadMinutes) "${remainMin}分钟后" else "马上"
        val loc = if (location.isBlank()) "" else "，地点 $location"
        return "$whenText 开始$loc"
    }
}

/**
 * F12 日程提醒：读系统日历（READ_CALENDAR 运行时权限，未授权时静默空结果）。
 * 提醒判定纯函数 [CalendarReader.filterUpcoming] 独立可测；
 * 已提醒事件 id 记录在 SharedPreferences（按日过期，隔天清空）。
 */
@javax.inject.Singleton
class CalendarReader @javax.inject.Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val app: Context,
) {

    /** 今天（本地时区）剩余日程（用于晨报） */
    suspend fun todayEvents(): List<CalendarEvent> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        runCatching { queryUpcoming(startOfDayMillis(), endOfDayMillis()) }.getOrDefault(emptyList())
    }

    /**
     * 未来 leadMinutes 内开始且尚未提醒过的事件（F12）。
     * 窗口 = [now, now + lead]，按 eventId + 当天日期去重。
     */
    suspend fun dueUnnotifiedEvents(leadMinutes: Int): List<CalendarEvent> {
        if (!hasPermission()) return emptyList()
        val now = System.currentTimeMillis()
        val all = runCatching { queryUpcoming(now, now + leadMinutes * 60_000L) }
            .getOrDefault(emptyList())
        return filterUpcoming(all, now, leadMinutes.toLong())
            .filter { it.eventId !in notifiedTodayIds() }
    }

    fun markNotified(event: CalendarEvent) {
        val prefs = prefs()
        val today = todayKey()
        // 按日过期：set 元素格式「YYYY-MM-DD:eventId」，写入前清掉非今天的
        val stored = prefs.getStringSet(KEY_NOTIFIED, emptySet()).orEmpty()
        val clean = stored.filter { it.startsWith("$today:") }.toSet()
        prefs.edit().putStringSet(KEY_NOTIFIED, clean + "$today:${event.eventId}").apply()
    }

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        app,
        Manifest.permission.READ_CALENDAR,
    ) == PackageManager.PERMISSION_GRANTED

    // ---- 纯函数区（单测覆盖） ----

    companion object {
        const val PREFS = "calendar_reminder"
        const val KEY_NOTIFIED = "notified_events"

        /** 纯函数：过滤「[now, now + leadMin 分钟] 内开始」的事件，按开始时间升序 */
        fun filterUpcoming(events: List<CalendarEvent>, now: Long, leadMinutes: Long): List<CalendarEvent> =
            events.filter { it.begin >= now && it.begin <= now + leadMinutes * 60_000L }
                .sortedBy { it.begin }

        private fun todayKey(): String = LocalDate.now().toString()
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun notifiedTodayIds(): Set<Long> {
        val today = todayKey()
        return prefs().getStringSet(KEY_NOTIFIED, emptySet()).orEmpty()
            .filter { it.startsWith("$today:") }
            .mapNotNull { it.substringAfter(':').toLongOrNull() }
            .toSet()
    }

    // ---- Android ContentResolver 查询 ----

    private fun queryUpcoming(fromMillis: Long, toMillis: Long): List<CalendarEvent> {
        val uri = android.net.Uri.parse(
            "content://com.android.calendar/instances/when/$fromMillis/$toMillis",
        )
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val events = mutableListOf<CalendarEvent>()
        runCatching {
            app.contentResolver.query(
                uri, projection, null, null,
                CalendarContract.Instances.BEGIN + " ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "（无标题日程）"
                    events.add(CalendarEvent(id, title, c.getLong(2), c.getLong(3), c.getString(4) ?: ""))
                }
            }
        }
        return events
    }

    private fun startOfDayMillis(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun endOfDayMillis(): Long =
        LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
}
