package com.lingxi

import com.lingxi.data.functions.AndroidActionExecutor
import com.lingxi.data.functions.ActionExecutor
import com.lingxi.data.LlmClient
import com.lingxi.data.LlmStream
import com.lingxi.data.SystemTtsEngine
import com.lingxi.data.TtsEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import android.content.Context
import javax.inject.Singleton

/** LlmStream/ActionExecutor 接口绑定：ConversationEngine 依赖抽象，测试用 fake 替换 */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {

    @Provides
    @Singleton
    fun provideLlmStream(): LlmStream = LlmClient()

    @Provides
    @Singleton
    fun provideTtsEngine(impl: SystemTtsEngine): TtsEngine = impl

    @Provides
    @Singleton
    fun provideActionExecutor(impl: AndroidActionExecutor): ActionExecutor = impl

    @Provides
    @Singleton
    fun provideScreenCaptor(impl: com.lingxi.data.screen.MediaStoreCaptor): com.lingxi.data.screen.ScreenCaptor = impl
}
