package com.lingxi.data.memory

import androidx.room.Database
import androidx.room.RoomDatabase
import com.lingxi.data.delegation.DelegationDao

@androidx.room.Database(
    entities = [
        TurnEntity::class,
        DailySummaryEntity::class,
        ProfileEntryEntity::class,
        InterestStatEntity::class,
        BehaviorPatternEntity::class,
        com.lingxi.data.delegation.DelegationTaskEntity::class,
    ],
    version = 4,
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

/** v2 → v3：委托任务表加检查器字段（R9 F13 真实检查器：taskType/paramsJson） */
val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `delegation_tasks` ADD COLUMN `taskType` TEXT NOT NULL DEFAULT 'GENERIC'")
        db.execSQL("ALTER TABLE `delegation_tasks` ADD COLUMN `paramsJson` TEXT NOT NULL DEFAULT '{}'")
    }
}

/** v3 → v4：兴趣画像 + 行为统计（R13，全本地统计不上云） */
val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `interest_stats` (" +
                "`category` TEXT NOT NULL PRIMARY KEY, " +
                "`weight` REAL NOT NULL, " +
                "`hitCount` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `behavior_patterns` (" +
                "`hourBucket` INTEGER NOT NULL, " +
                "`weekday` INTEGER NOT NULL, " +
                "`action` TEXT NOT NULL, " +
                "`count` INTEGER NOT NULL, " +
                "`ignoredCount` INTEGER NOT NULL, " +
                "`lastAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`hourBucket`, `weekday`, `action`))",
        )
    }
}
