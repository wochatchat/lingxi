package com.lingxi.di

import android.content.Context
import androidx.room.Room
import com.lingxi.data.delegation.DelegationDao
import com.lingxi.data.memory.MemoryDao
import com.lingxi.data.memory.MemoryDb
import com.lingxi.data.memory.MemoryRepository
import com.lingxi.data.memory.MemoryStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** F7 三层记忆：Room 数据库 + MemoryStore 接口绑定 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideMemoryDb(@ApplicationContext context: Context): MemoryDb =
        Room.databaseBuilder(context, MemoryDb::class.java, "lingxi_memory.db")
            .addMigrations(com.lingxi.data.memory.MIGRATION_1_2, com.lingxi.data.memory.MIGRATION_2_3)
            .build()

    @Provides
    fun provideMemoryDao(db: MemoryDb): MemoryDao = db.memoryDao()

    @Provides
    fun provideDelegationDao(db: MemoryDb): DelegationDao = db.delegationDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MemoryModule {

    @Binds
    @Singleton
    abstract fun bindMemoryStore(impl: MemoryRepository): MemoryStore
}

/** F13 委托任务检查器绑定（R9：CheckerRouter 按任务类型路由，快递走真实事件源） */
@Module
@InstallIn(SingletonComponent::class)
abstract class DelegationModule {

    @Binds
    @Singleton
    abstract fun bindTaskChecker(impl: com.lingxi.data.delegation.CheckerRouter): com.lingxi.data.delegation.TaskChecker
}
