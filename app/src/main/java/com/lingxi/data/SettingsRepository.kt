package com.lingxi.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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

    // ---- R7 主动服务（F11 晨报 / F12 日程提醒） ----

    private val morningReportEnabledKey = booleanPreferencesKey("morning_report_enabled")
    private val morningReportTimeKey = stringPreferencesKey("morning_report_time")
    private val morningReportCityKey = stringPreferencesKey("morning_report_city")
    private val lastMorningReportKey = stringPreferencesKey("last_morning_report")
    private val calendarReminderEnabledKey = booleanPreferencesKey("calendar_reminder_enabled")
    private val calendarLeadMinutesKey = intPreferencesKey("calendar_lead_minutes")

    /** F11 晨报开关（默认关） */
    val morningReportEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[morningReportEnabledKey] ?: false }

    /** F11 晨报时间（HH:mm，默认 08:00） */
    val morningReportTime: Flow<String> =
        context.settingsDataStore.data.map { it[morningReportTimeKey] ?: "08:00" }

    /** F11 晨报城市（空 = 不带天气） */
    val morningReportCity: Flow<String> =
        context.settingsDataStore.data.map { it[morningReportCityKey] ?: "" }

    /** 最近一次晨报全文（列表页存档展示） */
    val lastMorningReport: Flow<String> =
        context.settingsDataStore.data.map { it[lastMorningReportKey] ?: "" }

    /** F12 日程提醒开关（默认关；需要 READ_CALENDAR） */
    val calendarReminderEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[calendarReminderEnabledKey] ?: false }

    /** F12 提前提醒分钟数（默认 15） */
    val calendarLeadMinutes: Flow<Int> =
        context.settingsDataStore.data.map { it[calendarLeadMinutesKey] ?: 15 }

    suspend fun setMorningReportEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[morningReportEnabledKey] = enabled }
    }

    suspend fun setMorningReportTime(time: String) {
        context.settingsDataStore.edit { it[morningReportTimeKey] = time }
    }

    suspend fun setMorningReportCity(city: String) {
        context.settingsDataStore.edit { it[morningReportCityKey] = city }
    }

    suspend fun setLastMorningReport(report: String) {
        context.settingsDataStore.edit { it[lastMorningReportKey] = report }
    }

    suspend fun setCalendarReminderEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[calendarReminderEnabledKey] = enabled }
    }

    suspend fun setCalendarLeadMinutes(minutes: Int) {
        context.settingsDataStore.edit { it[calendarLeadMinutesKey] = minutes }
    }

    // ---- R8 工程面（F16 全离线 / F17 功耗 / F9 UI 代操作） ----

    private val offlineModeKey = booleanPreferencesKey("offline_mode_enabled")
    private val vadThresholdKey = intPreferencesKey("vad_threshold_rms")
    private val screenOffStopKey = booleanPreferencesKey("screen_off_stop_enabled")
    private val uiAssistEnabledKey = booleanPreferencesKey("ui_assist_enabled")

    /** F16 全离线模式（默认关）：开启后仅系统动作（闹钟/开App/提醒等）可用，云端对话停用 */
    val offlineModeEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[offlineModeKey] ?: false }

    /** F17 常听 VAD 能量阈值（RMS，默认 500；调高 = 降灵敏度省电） */
    val vadThreshold: Flow<Int> =
        context.settingsDataStore.data.map { it[vadThresholdKey] ?: 500 }

    /** F17 息屏全停：息屏时暂停常听麦克风 */
    val screenOffStopEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[screenOffStopKey] ?: false }

    /** F9 UI 代操作总开关（系统无障碍开关之外的软件侧闸门，默认关） */
    val uiAssistEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[uiAssistEnabledKey] ?: false }

    suspend fun setOfflineMode(enabled: Boolean) {
        context.settingsDataStore.edit { it[offlineModeKey] = enabled }
    }

    suspend fun setVadThreshold(threshold: Int) {
        context.settingsDataStore.edit { it[vadThresholdKey] = threshold }
    }

    suspend fun setScreenOffStopEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[screenOffStopKey] = enabled }
    }

    suspend fun setUiAssistEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[uiAssistEnabledKey] = enabled }
    }
}
