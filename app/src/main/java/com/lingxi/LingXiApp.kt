package com.lingxi

import android.app.Application
import android.content.Context
import android.content.Intent
import com.lingxi.data.Notifier
import com.lingxi.data.ConversationEngine
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
class LingXiApp : Application() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var delegation: DelegationRepository
    @Inject lateinit var engine: ConversationEngine
    @Inject lateinit var tts: com.lingxi.data.TtsEngine

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
                    if (on) {
                        FloatingCapsuleService.start(this@LingXiApp)
                        // R11 保活心跳：常驻开启期间 15min 自检拉起
                        com.lingxi.data.keepalive.KeepAliveWorker.schedule(this@LingXiApp, true)
                    } else {
                        com.lingxi.data.keepalive.KeepAliveWorker.schedule(this@LingXiApp, false)
                        FloatingCapsuleService.stop(this@LingXiApp)
                    }
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
        // R8 F16 全离线模式：设置 → 引擎（引擎不直接依赖 DataStore）
        appScope.launch {
            settings.offlineModeEnabled.distinctUntilChanged().collect { engine.setOfflineMode(it) }
        }
        // R12 F14 Agent 周期巡检：随设置启停（间隔最小 6h）
        appScope.launch {
            combine(settings.inspectionEnabled, settings.inspectionIntervalHours) { on, hours -> on to hours }
                .distinctUntilChanged()
                .collect { (on, hours) ->
                    if (on) delegation.scheduleInspection(hours)
                    else delegation.cancelInspection()
                }
        }
        // R8 F17 功耗采样：App 进程存活期间记录电量（BATTERY_CHANGED 是粘性广播，进程死掉即停，重启后继续）
        registerReceiver(batteryReceiver, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        // R11 语音降延迟：启动即预热 TTS 引擎（IO 线程，首次播报省 init 耗时）
        appScope.launch(Dispatchers.IO) { runCatching { tts.warmUp() } }
    }

    private val batteryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            if (level < 0) return
            val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
            runCatching {
                com.lingxi.data.power.PowerStore.addSample(context, level * 100 / scale, charging, System.currentTimeMillis())
            }
        }
    }
}
