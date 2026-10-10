package com.lingxi.data.memory

import androidx.room.Database
import androidx.room.RoomDatabase
import com.lingxi.data.delegation.DelegationDao

@androidx.room.Database(
    entities = [
        TurnEntity::class,
        DailySummaryEntity::class,
        ProfileEntryEntity::class,
        com.lingxi.data.delegation.DelegationTaskEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class MemoryDb : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
    abstract fun delegationDao(): DelegationDao
}

/** v1 → v2：新增委托任务表（F13） */
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `delegation_tasks` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`kind` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`triggerAt` INTEGER NOT NULL, " +
                "`intervalMinutes` INTEGER NOT NULL, " +
                "`lastResult` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)",
        )
    }
}
