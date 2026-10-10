package com.lingxi

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.lingxi.data.Notifier
import com.lingxi.data.SettingsRepository
import com.lingxi.data.delegation.DelegationRepository
import com.lingxi.service.FloatingCapsuleService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalTime
import javax.inject.Inject

@HiltAndroidApp
class LingXiApp : Application(), androidx.work.Configuration.Provider {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var delegation: DelegationRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** WorkManager + Hilt：worker 通过 HiltWorkerFactory 注入依赖 */
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        com.lingxi.data.Notifier.ensureChannel(this)
        // R3 常驻形态开关：胶囊或常听任一开启 → 起前台服务；全关 → 停止
        appScope.launch {
            combine(
                settings.capsuleEnabled,
                settings.alwaysListenEnabled,
            ) { capsule, listen -> capsule || listen }
                .distinctUntilChanged()
                .collect { on ->
                    if (on) FloatingCapsuleService.start(this@LingXiApp)
                    else FloatingCapsuleService.stop(this@LingXiApp)
                }
        }
        // R7 主动服务：晨报（F11）+ 日程提醒（F12）随设置启停
        appScope.launch {
            combine(settings.morningReportEnabled, settings.morningReportTime) { on, time -> on to time }
                .distinctUntilChanged()
                .collect { (on, time) ->
                    if (on) {
                        runCatching {
                            val (h, m) = time.split(":").map { it.trim().toInt() }
                            delegation.scheduleMorningReport(LocalTime.of(h.coerceIn(0, 23), m.coerceIn(0, 59)))
                        }.onFailure { delegation.cancelMorningReport() }
                    } else {
                        delegation.cancelMorningReport()
                    }
                }
        }
        appScope.launch {
            settings.calendarReminderEnabled
                .distinctUntilChanged()
                .collect { on ->
                    if (on) delegation.scheduleCalendarReminder()
                    else delegation.cancelCalendarReminder()
                }
        }
    }
}
