package com.lingxi.data.keepalive

import android.content.Context
import android.content.SharedPreferences
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lingxi.data.Notifier
import com.lingxi.data.SettingsRepository
import com.lingxi.service.FloatingCapsuleService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import java.util.concurrent.TimeUnit

/**
 * R11 保活心跳（15min 周期，WorkManager 自身跨重启持久）：
 * 用户要常驻但胶囊前台服务死了 → 拉起；后台启动被系统拒绝（Android 12+ 限制）→
 * 发「恢复」通知引导用户点开 App（回到前台后 LingXiApp 的开关收集会自动重启服务）。
 *
 * 不用 @HiltWorker/@AssistedInject（R7 教训：KSP 2.0.20-1.0.25 + Dagger 2.52 不兼容），
 * 沿用默认反射工厂 + EntryPoint 现取依赖。
 */
class KeepAliveWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entry = entry()
        val settings = entry.settings()
        val wantService = settings.capsuleEnabled.first() || settings.alwaysListenEnabled.first()
        val alive = FloatingCapsuleService.isRunning
        if (!KeepAlivePolicy.shouldRevive(alive, wantService)) return Result.success()

        runCatching { FloatingCapsuleService.start(applicationContext) }
            .onFailure {
                // 后台启 FGS 被拒：降级为恢复通知（60min 节流，防心跳刷屏）
                notifyRestore(entry.notifier())
            }
        return Result.success()
    }

    private fun notifyRestore(notifier: Notifier) {
        val prefs = prefs(applicationContext)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_NOTIFY, 0L)
        if (now - last < NOTIFY_THROTTLE_MS) return
        prefs.edit().putLong(KEY_LAST_NOTIFY, now).apply()
        notifier.post(
            "灵犀已停止运行",
            "常驻服务被系统回收了，点按这里回到灵犀即可自动恢复（也可在系统设置中授予自启/电池豁免）。",
        )
    }

    private fun entry(): KeepAliveEntryPoint =
        EntryPointAccessors.fromApplication(applicationContext, KeepAliveEntryPoint::class.java)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("keepalive", Context.MODE_PRIVATE)

    @EntryPoint
    @InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface KeepAliveEntryPoint {
        fun settings(): SettingsRepository
        fun notifier(): Notifier
    }

    companion object {
        private const val WORK_NAME = "lingxi-keepalive"
        private const val KEY_LAST_NOTIFY = "last_restore_notify"
        private const val NOTIFY_THROTTLE_MS = 60L * 60 * 1000

        /** 常驻开启时调度心跳；全关时取消（心跳很轻，只在常驻开启期间跑） */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (enabled) {
                val request = PeriodicWorkRequestBuilder<KeepAliveWorker>(15, TimeUnit.MINUTES).build()
                wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                wm.cancelUniqueWork(WORK_NAME)
            }
        }
    }
}
