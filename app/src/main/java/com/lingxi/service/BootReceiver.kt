package com.lingxi.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lingxi.data.SettingsRepository
import com.lingxi.data.keepalive.KeepAlivePolicy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * R11 开机/应用更新自启：
 * - BOOT_COMPLETED（FBE 设备在用户解锁后广播，DataStore 可读）+ QUICKBOOT_POWERON（部分 OEM 快启）
 *   + MY_PACKAGE_REPLACED（应用更新后恢复常驻）。
 * - Android 14+ 禁止从 BOOT 启动 microphone 类 FGS：这里只起 specialUse 基座；
 *   常听的 mic 叠加由服务内 USER_PRESENT 重试逻辑在解锁后补上。
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settings: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val autoStart = settings.autoStartOnBoot.first()
                val capsule = settings.capsuleEnabled.first()
                val listen = settings.alwaysListenEnabled.first()
                if (KeepAlivePolicy.shouldStartOnBoot(autoStart, capsule, listen)) {
                    FloatingCapsuleService.start(context)
                }
            } catch (_: Exception) {
                // 系统广播窗口内失败只放弃本轮，心跳 worker 会兜底重试
            } finally {
                pending.finish()
            }
        }
    }
}
