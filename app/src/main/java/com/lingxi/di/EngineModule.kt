package com.lingxi.di

import com.lingxi.data.LlmClient
import com.lingxi.data.LlmStream
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** LlmStream 接口绑定：ConversationEngine 依赖抽象，测试用 fake 替换 */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {

    @Provides
    @Singleton
    fun provideLlmStream(): LlmStream = LlmClient()
}
