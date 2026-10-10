package com.lingxi.data.delegation

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 委托任务类型（F13） */
object TaskKind {
    /** 一次性提醒：到点通知 */
    const val REMINDER = "REMINDER"

    /** 周期巡查：间隔轮询，结果回写 lastResult */
    const val POLL = "POLL"
}

/**
 * 委托任务检查器类型（R9 F13 真实检查器路由用）：
 * GENERIC 走默认占位检查器；EXPRESS 快递跟踪（快递100）。
 */
object TaskType {
    const val GENERIC = "GENERIC"
    const val EXPRESS = "EXPRESS"
}

/**
 * paramsJson 的解析视图（纯函数，可单测）。
 * EXPRESS：{"tracking_no":"SF1234567890","company":"shunfeng"}
 */
object TaskParams {

    fun trackingNo(json: String): String =
        runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(json)
                .let { it as? kotlinx.serialization.json.JsonObject }
                ?.get("tracking_no")
                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                ?.content
        }.getOrNull().orEmpty()

    fun company(json: String): String =
        runCatching {
            (kotlinx.serialization.json.Json.parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject)
                ?.get("company")
                ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                ?.content
        }.getOrNull().orEmpty()

    fun encodeExpress(trackingNo: String, company: String): String =
        """{"tracking_no":"$trackingNo","company":"$company"}"""
}

/** 委托任务状态 */
object TaskStatus {
    const val ACTIVE = "ACTIVE"
    const val PAUSED = "PAUSED"
    const val DONE = "DONE"
    const val CANCELLED = "CANCELLED"
}

/**
 * 委托任务（F13）：「盯着 XX 到了告诉我」这类后台跟踪任务。
 * REMINDER 一次性到点提醒；POLL 周期巡查（检查器在 R8+ 逐步接入真实事件源）。
 */
@androidx.room.Entity(tableName = "delegation_tasks")
data class DelegationTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** TaskKind.REMINDER / TaskKind.POLL */
    val kind: String,
    /** TaskStatus.ACTIVE / PAUSED / DONE / CANCELLED */
    val status: String,
    /** REMINDER：触发时间（epoch ms）；POLL 为 0 */
    val triggerAt: Long = 0,
    /** POLL：巡查间隔（分钟，最小 15 由 WorkManager 限制） */
    val intervalMinutes: Int = 0,
    /** 最近一次触发/巡查结果（卡片存档） */
    val lastResult: String = "",
    /** TaskType.GENERIC / EXPRESS（R9 真实检查器路由） */
    val taskType: String = TaskType.GENERIC,
    /** 检查器参数 JSON（EXPRESS：tracking_no/company） */
    val paramsJson: String = "{}",
    val createdAt: Long,
    val updatedAt: Long,
)

@androidx.room.Dao
interface DelegationDao {

    @androidx.room.Insert
    suspend fun insert(task: DelegationTaskEntity): Long

    @androidx.room.Query("SELECT * FROM delegation_tasks ORDER BY updatedAt DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<DelegationTaskEntity>>

    @androidx.room.Query("SELECT * FROM delegation_tasks WHERE status = 'ACTIVE' ORDER BY triggerAt ASC")
    suspend fun activeTasks(): List<DelegationTaskEntity>

    @androidx.room.Query("SELECT * FROM delegation_tasks WHERE id = :id")
    suspend fun byId(id: Long): DelegationTaskEntity?

    @androidx.room.Query(
        "SELECT * FROM delegation_tasks WHERE status IN ('ACTIVE','PAUSED') " +
            "AND title LIKE '%' || :title || '%' ORDER BY id DESC LIMIT 1",
    )
    suspend fun findActiveByTitle(title: String): DelegationTaskEntity?

    @androidx.room.Query(
        "UPDATE delegation_tasks SET status = :status, lastResult = :lastResult, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateStatus(id: Long, status: String, lastResult: String, updatedAt: Long)

    @androidx.room.Query(
        "UPDATE delegation_tasks SET lastResult = :lastResult, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateResult(id: Long, lastResult: String, updatedAt: Long)

    @androidx.room.Query(
        "UPDATE delegation_tasks SET intervalMinutes = :intervalMinutes, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateInterval(id: Long, intervalMinutes: Int, updatedAt: Long)

    @androidx.room.Query("SELECT COUNT(*) FROM delegation_tasks WHERE status = 'ACTIVE'")
    fun observeActiveCount(): kotlinx.coroutines.flow.Flow<Int>
}
