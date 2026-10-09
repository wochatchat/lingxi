package com.lingxi.data.memory

import androidx.room.Database
import androidx.room.RoomDatabase

@androidx.room.Database(
    entities = [TurnEntity::class, DailySummaryEntity::class, ProfileEntryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MemoryDb : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao
}
