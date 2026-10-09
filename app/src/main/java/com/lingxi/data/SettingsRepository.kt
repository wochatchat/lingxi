package com.lingxi.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore("settings")

/**
 * 常规开关设置（R3 常驻形态）：
 * - 悬浮胶囊（耳语胶囊 FloatingCapsuleService）
 * - 常听模式（麦克风常听 + 能量 VAD，G0 级开关）
 * 默认全部关闭，用户在设置页显式开启。
 */
@Singleton
class SettingsRepository @javax.inject.Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val capsuleKey = booleanPreferencesKey("capsule_enabled")
    private val alwaysListenKey = booleanPreferencesKey("always_listen_enabled")
    private val firstLaunchDoneKey = booleanPreferencesKey("first_launch_done")

    /** F15 首启引导：完成过引导页（默认 false = 待引导） */
    val firstLaunchDone: Flow<Boolean> = context.settingsDataStore.data.map { it[firstLaunchDoneKey] ?: false }

    val capsuleEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[capsuleKey] ?: false }
    val alwaysListenEnabled: Flow<Boolean> = context.settingsDataStore.data.map { it[alwaysListenKey] ?: false }

    suspend fun setCapsuleEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[capsuleKey] = enabled }
    }

    suspend fun setAlwaysListenEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[alwaysListenKey] = enabled }
    }

    suspend fun setFirstLaunchDone(done: Boolean) {
        context.settingsDataStore.edit { it[firstLaunchDoneKey] = done }
    }
}
