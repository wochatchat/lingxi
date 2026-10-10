package com.lingxi.data.delegation

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 委托任务仓库（F13）：Room 落库 + WorkManager 调度。
 * 调度策略：
 * - REMINDER → 一次性 worker（unique name reminder-<id>，延迟到触发时刻）
 * - POLL → 周期 worker（unique name poll-<id>，间隔 = intervalMinutes，最小 15 分钟）
 * - 晨报（F11）→ 一次性自续订（每天跑完排下一次，保证在配置时刻触发）
 */
@Singleton
class DelegationRepository @Inject constructor(
    @ApplicationContext private val app: Context,
    private val dao: DelegationDao,
) {

    private val workManager: WorkManager get() = WorkManager.getInstance(app)

    fun observeAll(): Flow<List<DelegationTaskEntity>> = dao.observeAll()

    fun observeActiveCount(): Flow<Int> = dao.observeActiveCount()

    suspend fun activeTasks(): List<DelegationTaskEntity> = dao.activeTasks()

    /** 创建一次性提醒任务并调度（taskType/paramsJson：R9 真实检查器用） */
    suspend fun createReminder(
        title: String,
        triggerAt: Long,
        taskType: String = TaskType.GENERIC,
        paramsJson: String = "{}",
    ): DelegationTaskEntity {
        val now = System.currentTimeMillis()
        val task = DelegationTaskEntity(
            title = title,
            kind = TaskKind.REMINDER,
            status = TaskStatus.ACTIVE,
            triggerAt = triggerAt,
            lastResult = "",
            taskType = taskType,
            paramsJson = paramsJson,
            createdAt = now,
            updatedAt = now,
        )
        val id = dao.insert(task)
        scheduleReminder(id, triggerAt)
        return task.copy(id = id)
    }

    /** 创建周期巡查任务并调度（taskType/paramsJson：R9 真实检查器用） */
    suspend fun createPoll(
        title: String,
        intervalMinutes: Int,
        taskType: String = TaskType.GENERIC,
        paramsJson: String = "{}",
    ): DelegationTaskEntity {
        val now = System.currentTimeMillis()
        val interval = intervalMinutes.coerceAtLeast(15)
        val task = DelegationTaskEntity(
            title = title,
            kind = TaskKind.POLL,
            status = TaskStatus.ACTIVE,
            intervalMinutes = interval,
            lastResult = "",
            taskType = taskType,
            paramsJson = paramsJson,
            createdAt = now,
            updatedAt = now,
        )
        val id = dao.insert(task)
        schedulePoll(id, interval)
        return task.copy(id = id)
    }

    /** R9 ParamPanel：调整巡查间隔并重排 worker */
    suspend fun updateInterval(id: Long, intervalMinutes: Int): Boolean {
        val task = dao.byId(id) ?: return false
        val interval = intervalMinutes.coerceAtLeast(15)
        dao.updateInterval(id, interval, System.currentTimeMillis())
        schedulePoll(id, interval)
        return true
    }

    suspend fun pause(id: Long): Boolean {
        val task = dao.byId(id) ?: return false
        dao.updateStatus(id, TaskStatus.PAUSED, task.lastResult, System.currentTimeMillis())
        workManager.cancelUniqueWork(uniqueName(task))
        return true
    }

    suspend fun resume(id: Long): Boolean {
        val task = dao.byId(id) ?: return false
        dao.updateStatus(id, TaskStatus.ACTIVE, task.lastResult, System.currentTimeMillis())
        reschedule(task)
        return true
    }

    suspend fun cancel(id: Long): Boolean {
        val task = dao.byId(id) ?: return false
        dao.updateStatus(id, TaskStatus.CANCELLED, task.lastResult, System.currentTimeMillis())
        workManager.cancelUniqueWork(uniqueName(task))
        return true
    }

    /** 完成（worker 触发或用户手动收尾） */
    suspend fun complete(id: Long, result: String) {
        dao.updateStatus(id, TaskStatus.DONE, result, System.currentTimeMillis())
        dao.byId(id)?.let { workManager.cancelUniqueWork(uniqueName(it)) }
    }

    suspend fun appendResult(id: Long, result: String) {
        dao.updateResult(id, result, System.currentTimeMillis())
    }

    suspend fun byId(id: Long): DelegationTaskEntity? = dao.byId(id)

    suspend fun findActiveByTitle(title: String): DelegationTaskEntity? = dao.findActiveByTitle(title)

    /** 任务创建/恢复后按 kind 重新调度 */
    fun reschedule(task: DelegationTaskEntity) {
        when (task.kind) {
            TaskKind.REMINDER -> scheduleReminder(task.id, task.triggerAt)
            TaskKind.POLL -> schedulePoll(task.id, task.intervalMinutes)
        }
    }

    /** F11 晨报：每天在配置时刻触发（一次性自续订） */
    fun scheduleMorningReport(time: LocalTime) {
        val delay = delayUntil(time)
        val request = OneTimeWorkRequestBuilder<MorningReportWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(WORK_MORNING_REPORT, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelMorningReport() {
        workManager.cancelUniqueWork(WORK_MORNING_REPORT)
    }

    /** F12 日程提醒：每 15 分钟检查一次即将开始的事件 */
    fun scheduleCalendarReminder() {
        val request = PeriodicWorkRequestBuilder<CalendarReminderWorker>(15, TimeUnit.MINUTES).build()
        workManager.enqueueUniquePeriodicWork(WORK_CALENDAR, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancelCalendarReminder() {
        workManager.cancelUniqueWork(WORK_CALENDAR)
    }

    private fun scheduleReminder(id: Long, triggerAt: Long) {
        val delay = (triggerAt - System.currentTimeMillis()).coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(androidx.work.workDataOf(ReminderWorker.KEY_TASK_ID to id))
            .build()
        workManager.enqueueUniqueWork("reminder-$id", ExistingWorkPolicy.REPLACE, request)
    }

    private fun schedulePoll(id: Long, intervalMinutes: Int) {
        val request = PeriodicWorkRequestBuilder<PollWorker>(intervalMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
            .setInputData(androidx.work.workDataOf(ReminderWorker.KEY_TASK_ID to id))
            .build()
        workManager.enqueueUniquePeriodicWork("poll-$id", ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun uniqueName(task: DelegationTaskEntity) = when (task.kind) {
        TaskKind.REMINDER -> "reminder-${task.id}"
        else -> "poll-${task.id}"
    }

    /** 距离下一个配置时刻（今天或明天 HH:mm）的毫秒数 */
    private fun delayUntil(time: LocalTime, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = java.time.ZonedDateTime.now(zone)
        var next = now.toLocalDate().atTime(time).atZone(zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return java.time.Duration.between(now, next).toMillis()
    }

    companion object {
        const val WORK_MORNING_REPORT = "morning_report"
        const val WORK_CALENDAR = "calendar_reminder"
    }
}

/**
 * 委托任务检查器扩展点（F13 v1 占位，R8+ 接真实事件源：快递/航班/比价等）。
 * 返回非空字符串表示「任务有了新结果」→ 标记 DONE 并通知；null 表示暂无更新。
 */
interface TaskChecker {
    suspend fun check(task: DelegationTaskEntity): TaskUpdate?
}

/** v1 默认检查器：无真实事件源，永远返回 null（仅巡查打卡） */
@javax.inject.Singleton
class DefaultTaskChecker @Inject constructor() : TaskChecker {
    override suspend fun check(task: DelegationTaskEntity): TaskUpdate? = null
}
