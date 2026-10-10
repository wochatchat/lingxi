package com.lingxi.data.delegation

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lingxi.data.Notifier
import com.lingxi.data.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.LocalTime
import java.time.ZoneId

/**
 * F13 一次性提醒 worker：到点标记完成 + 通知（结果播报走通知通道，不抢前台对话）。
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: DelegationRepository,
    private val notifier: Notifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_TASK_ID, -1)
        if (id <= 0) return Result.failure()
        val task = repository.byId(id) ?: return Result.success()
        if (task.status != TaskStatus.ACTIVE) return Result.success()
        repository.complete(id, "已提醒（${TaskTime.formatAt(System.currentTimeMillis(), ZoneId.systemDefault())}）")
        notifier.post("委托提醒：${task.title}", "你之前让我提醒的事到时间了：${task.title}")
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
@HiltWorker
class PollWorker @AssistedInject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: DelegationRepository,
    private val checker: TaskChecker,
    private val notifier: Notifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(ReminderWorker.KEY_TASK_ID, -1)
        if (id < 0) return Result.failure()
        val task = repository.byId(id) ?: return Result.success()
        if (task.status != TaskStatus.ACTIVE) return Result.success()

        val update = runCatching { checker.check(task) }.getOrNull()
        return if (update != null) {
            repository.complete(id, update)
            notifier.post("委托有结果：${task.title}", update)
            Result.success()
        } else {
            repository.appendResult(
                id,
                "已巡查 ${TaskTime.formatAt(System.currentTimeMillis(), ZoneId.systemDefault())}，暂无更新",
            )
            Result.success()
        }
    }
}

/**
 * F11 晨报 worker：聚合天气 + 今日日程 + 委托任务进展 → 通知 + 存档。
 * 一次性自续订：跑完立即排下一次（设置里改时间/开关时由 App 层 REPLACE 重排）。
 */
@HiltWorker
class MorningReportWorker @AssistedInject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext appContext: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsRepository,
    private val repository: DelegationRepository,
    private val weather: WeatherClient,
    private val calendar: CalendarReader,
    private val composer: MorningReportComposer,
    private val notifier: Notifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (settings.morningReportEnabled.first()) {
            val city = settings.morningReportCity.first()
            val weatherLine = city.takeIf { it.isNotBlank() }?.let { weather.currentSummary(it) }
            val events = calendar.todayEvents().map { it.display() }
            val active = repository.activeTasks()
            val report = composer.compose(
                MorningReportComposer.Inputs(
                    dateLine = TaskTime.formatAt(System.currentTimeMillis(), ZoneId.systemDefault()),
                    weather = weatherLine,
                    events = events,
                    activeTasks = active.map { it.title },
                ),
            )
            settings.setLastMorningReport(report)
            notifier.post("灵犀晨报", report)
            // 自续订：排下一次（开关关闭时由 App 层 cancel，这里不再续排）
            runCatching {
                val (h, m) = settings.morningReportTime.first().split(":").map { it.trim().toInt() }
                repository.scheduleMorningReport(LocalTime.of(h.coerceIn(0, 23), m.coerceIn(0, 59)))
            }
        }
        return Result.success()
    }
}

/**
 * F12 日程提醒 worker：每 15 分钟检查未来 N 分钟内开始、尚未提醒过的日历事件。
 * 未授权 READ_CALENDAR 时静默跳过。
 */
@HiltWorker
class CalendarReminderWorker @AssistedInject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext appContext: Context,
    @Assisted params: WorkerParameters,
    private val settings: SettingsRepository,
    private val calendar: CalendarReader,
    private val notifier: Notifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val leadMinutes = settings.calendarLeadMinutes.first()
        val events = calendar.dueUnnotifiedEvents(leadMinutes)
        for (event in events) {
            notifier.post("日程提醒：${event.title}", event.describe(leadMinutes))
            calendar.markNotified(event)
        }
        return Result.success()
    }
}
