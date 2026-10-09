package com.lingxi.data.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    // ---- 会话层 ----

    @Insert
    suspend fun insertTurn(turn: TurnEntity): Long

    /** 最近 N 轮（降序取出，调用方反转成时间升序） */
    @Query("SELECT * FROM turns ORDER BY id DESC LIMIT :limit")
    suspend fun recentTurnsRaw(limit: Int): List<TurnEntity>

    @Query("SELECT * FROM turns WHERE summarized = 0 AND createdAt < :beforeMs ORDER BY id ASC LIMIT :limit")
    suspend fun pendingTurns(beforeMs: Long, limit: Int): List<TurnEntity>

    @Query("UPDATE turns SET summarized = 1 WHERE id IN (:ids)")
    suspend fun markSummarized(ids: List<Long>)

    @Query("DELETE FROM turns")
    suspend fun clearTurns()

    // ---- 每日摘要层 ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSummary(summary: DailySummaryEntity)

    @Query("SELECT * FROM daily_summaries ORDER BY date DESC LIMIT :limit")
    suspend fun recentSummaries(limit: Int): List<DailySummaryEntity>

    @Query("SELECT * FROM daily_summaries ORDER BY date DESC LIMIT :limit")
    fun summariesFlow(limit: Int): Flow<List<DailySummaryEntity>>

    @Query("SELECT MAX(date) FROM daily_summaries")
    suspend fun lastSummaryDate(): String?

    /** 滚动窗口：只保留最近 keep 天 */
    @Query(
        "DELETE FROM daily_summaries WHERE date NOT IN " +
            "(SELECT date FROM daily_summaries ORDER BY date DESC LIMIT :keep)"
    )
    suspend fun pruneSummaries(keep: Int)

    // ---- 长期画像层 ----

    @Insert
    suspend fun insertProfile(entry: ProfileEntryEntity): Long

    @Query("SELECT content FROM profile_entries WHERE enabled = 1 ORDER BY id ASC")
    suspend fun enabledProfileContents(): List<String>

    @Query("SELECT content FROM profile_entries")
    suspend fun allProfileContents(): List<String>

    @Query("SELECT * FROM profile_entries ORDER BY id DESC")
    fun profileEntries(): Flow<List<ProfileEntryEntity>>

    @Query("UPDATE profile_entries SET enabled = :enabled WHERE id = :id")
    suspend fun setProfileEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM profile_entries WHERE id = :id")
    suspend fun deleteProfile(id: Long)

    @Query("DELETE FROM profile_entries")
    suspend fun clearProfile()
}
