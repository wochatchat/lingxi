package com.lingxi.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lingxi.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 主动服务通知（F11 晨报 / F12 日程 / F13 委托结果）：
 * 单通道 proactive，点击打开主页；未授权通知权限时静默跳过。
 */
@Singleton
class Notifier @Inject constructor(
    @ApplicationContext private val app: Context,
) {

    /** title 相同的通知复用同一 id（互相替换不堆积） */
    fun post(title: String, body: String) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            app,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        if (!granted) return

        val intent = Intent(app, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pending = PendingIntent.getActivity(
            app,
            title.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_PROACTIVE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull().orEmpty().take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(app).notify(title.hashCode(), notification)
        }
    }

    companion object {
        const val CHANNEL_PROACTIVE = "proactive"
        private const val NOTIF_ID_BASE = 47000

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_PROACTIVE) != null) return
            val channel = NotificationChannel(
                CHANNEL_PROACTIVE,
                "主动提醒（晨报/日程/委托）",
                NotificationManager.IMPORTANCE_HIGH,
            )
            manager.createNotificationChannel(channel)
        }

        /** 稳定的通知 id（由 title hash 派生，避免碰撞到系统保留区） */
        fun stableId(title: String) = NOTIF_ID_BASE + (title.hashCode() and 0x7fffffff) % 10000
    }
}
