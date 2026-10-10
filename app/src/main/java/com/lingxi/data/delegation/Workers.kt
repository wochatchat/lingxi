package com.lingxi.data.delegation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lingxi.data.Notifier
import com.lingxi.data.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import java.time.LocalTime
import java.time.ZoneId

/**
 * Worker 依赖入口（不用 @HiltWorker/@AssistedInject，规避 KSP 版本兼容问题）：
 * worker 用默认反射工厂创建，依赖在 doWork 里通过 EntryPoint 现取。
 */
@EntryPoint
@InstallIn(dagger.hilt.components.SingletonComponent::class)
interface WorkerEntryPoint {
    fun delegation(): DelegationRepository
    fun settings(): com.lingxi.data.SettingsRepository
    fun notifier(): com.lingxi.data.Notifier
    fun weather(): WeatherClient
    fun calendar(): CalendarReader
    fun composer(): MorningReportComposer
    fun checker(): TaskChecker
}

/** worker 内取依赖入口的小工具 */
private fun Context.entry(): WorkerEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, WorkerEntryPoint::class.java)

/**
 * F13 一次性提醒 worker：到点标记完成 + 通知（结果播报走通知通道，不抢前台对话）。
 */
class ReminderWorker(appContext: Context, params: WorkerParameters) :
    androidx.work.CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_TASK_ID, -1)
        if (id <= 0) return Result.failure()
        val entry = entry()
        val task = entry.delegation().byId(id) ?: return Result.success()
        if (task.status != TaskStatus.ACTIVE) return Result.success()
        entry.delegation().complete(
            id,
            "已提醒（${TaskTime.formatAt(System.currentTimeMillis(), ZoneId.systemDefault())}）",
        )
        entry.notifier().post(
            "委托提醒：${task.title}",
            "你之前让我提醒的事到时间了：${task.title}",
        )
        return Result.success()
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
    }
}

/**
 * F13 周期巡查 worker：按任务间隔轮询检查器。
 * v1 检查器无真实事件源（DefaultTaskChecker 恒 null）→ 只打卡巡查记录；
 * R8+ 接快递/航班/比价等真实检查器后，有结果即标记 DONE 并通知。
 */
class PollWorker(appContext: Context, params: WorkerParameters) :
    androidx.work.CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(ReminderWorker.KEY_TASK_ID, -1)
        if (id < 0) return Result.failure()
        val entry = entry()
        val task = entry.delegation().byId(id) ?: return Result.success()
        if (task.status != TaskStatus.ACTIVE) return Result.success()

        val update = runCatching { entry.checker().check(task) }.getOrNull()
        return if (update != null) {
            entry.delegation().complete(id, update)
            entry.notifier().post("委托有结果：${task.title}", update)
            Result.success()
        } else {
            entry.delegation().appendResult(
                id,
                "已巡查 ${TaskTime.formatAt(System.currentTimeMillis(), java.time.ZoneId.systemDefault())}，暂无更新",
            )
            Result.success()
        }
    }
}

/**
 * F11 晨报 worker：聚合天气 + 今日日程 + 委托任务进展 → 通知 + 存档。
 * 一次性自续订：跑完立即排下一次（开关关闭时不再续排，App 层也会 cancel）。
 */
class MorningReportWorker(appContext: Context, params: WorkerParameters) :
    androidx.work.CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entry = entry()
        val settings = entry.settings()
        if (settings.morningReportEnabled.first()) {
            val city = settings.morningReportCity.first()
            val weatherLine = city.takeIf { it.isNotBlank() }?.let { entry.weather().currentSummary(it) }
            val events = entry.calendar().todayEvents().map { it.display() }
            val active = entry.delegation().activeTasks()
            val report = entry.composer().compose(
                MorningReportComposer.Inputs(
                    dateLine = TaskTime.formatAt(System.currentTimeMillis(), java.time.ZoneId.systemDefault()),
                    weather = weatherLine,
                    events = events,
                    activeTasks = active.map { it.title },
                ),
            )
            settings.setLastMorningReport(report)
            entry.notifier().post("灵犀晨报", report)
            // 自续订：排下一次（开关关闭时由 App 层 cancel，这里不再续排）
            runCatching {
                val (h, m) = settings.morningReportTime.first().split(":").map { it.trim().toInt() }
                entry.delegation().scheduleMorningReport(LocalTime.of(h.coerceIn(0, 23), m.coerceIn(0, 59)))
            }
        }
        return Result.success()
    }
}

/**
 * F12 日程提醒 worker：每 15 分钟检查未来 N 分钟内开始、尚未提醒过的日历事件。
 * 未授权 READ_CALENDAR 时静默跳过。
 */
class CalendarReminderWorker(appContext: Context, params: WorkerParameters) :
    androidx.work.CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, WorkerEntryPoint::class.java)
        val settings = entry.settings()
        val leadMinutes = settings.calendarLeadMinutes.first()
        val events = entry.calendar().dueUnnotifiedEvents(leadMinutes)
        for (event in events) {
            entry.notifier().post("日程提醒：${event.title}", event.describe(leadMinutes))
            entry.calendar().markNotified(event)
        }
        return Result.success()
    }
}
