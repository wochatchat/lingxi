package com.lingxi.data.delegation

import com.lingxi.data.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 检查器结果（R9）：Progress 有新进展（任务继续跑），Final 已完结（任务标 DONE）。
 */
sealed interface TaskUpdate {
    data class Progress(val text: String) : TaskUpdate
    data class Final(val text: String) : TaskUpdate
}

/**
 * 委托任务检查器扩展点（F13）：按任务类型路由到真实事件源。
 * 返回 null 表示暂无更新。
 */
interface TaskChecker {
    suspend fun check(task: DelegationTaskEntity): TaskUpdate?
}

/** v1 默认检查器：无真实事件源，永远返回 null（仅巡查打卡） */
@javax.inject.Singleton
class DefaultTaskChecker @Inject constructor() : TaskChecker {
    override suspend fun check(task: DelegationTaskEntity): TaskUpdate? = null
}

/**
 * 检查器路由器（R9）：EXPRESS → 快递100；GENERIC → 默认占位。
 * 快递 customer/key 未配置时返回 null（任务继续打卡巡查，不影响链路）。
 */
@Singleton
class CheckerRouter @Inject constructor(
    private val settings: SettingsRepository,
    private val kuaidi: Kuaidi100Client,
) : TaskChecker {

    override suspend fun check(task: DelegationTaskEntity): TaskUpdate? = when (task.taskType) {
        TaskType.EXPRESS -> checkExpress(task)
        else -> null
    }

    private suspend fun checkExpress(task: DelegationTaskEntity): TaskUpdate? {
        val trackingNo = TaskParams.trackingNo(task.paramsJson)
            .ifBlank { ExpressTracking.extractTrackingNo(task.title) ?: return null }
        val company = TaskParams.company(task.paramsJson).ifBlank { ExpressTracking.guessCompany(task.title) }
        val customer = settings.expressCustomer.first()
        val key = settings.expressKey.first()
        if (customer.isBlank() || key.isBlank()) return null
        val body = kuaidi.query(customer, key, company, trackingNo) ?: return null
        val summary = ExpressTracking.summarize(body, trackingNo) ?: return null
        // 与上次结果相同 → 不算新进展（防重复打扰）
        if (summary == task.lastResult) return null
        return if (ExpressTracking.isFinal(body)) TaskUpdate.Final(summary) else TaskUpdate.Progress(summary)
    }
}
