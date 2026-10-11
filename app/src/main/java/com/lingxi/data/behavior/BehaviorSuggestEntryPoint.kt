package com.lingxi.data.behavior

import com.lingxi.data.Notifier
import com.lingxi.data.SettingsRepository
import com.lingxi.data.memory.MemoryRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** R13 建议通知 worker 的依赖入口（沿用反射工厂 + EntryPoint 模式，规避 hilt-work 兼容问题） */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface BehaviorSuggestEntryPoint {
    fun settings(): SettingsRepository
    fun memory(): MemoryRepository
    fun notifier(): Notifier
}
