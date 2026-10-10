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
}
