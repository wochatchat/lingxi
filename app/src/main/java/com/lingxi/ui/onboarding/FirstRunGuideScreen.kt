package com.lingxi.ui.onboarding

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lingxi.util.BrandGuide
import com.lingxi.util.GENERIC
import com.lingxi.util.brandGuideFor

/**
 * F15 首启引导（单屏 checklist，PRD：厂商白名单 + 悬浮窗/麦克风/通知权限）。
 * 完成后写 firstLaunchDone，之后不再出现。
 */
@Composable
fun FirstRunGuideScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // 回到前台后重查权限状态（用户跳系统页开权限回来）
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val micOk = remember(refresh) { hasMic(context) }
    val notifyOk = remember(refresh) { NotificationManagerCompat.from(context).areNotificationsEnabled() }
    val overlayOk = remember(refresh) { SystemSettings.canDrawOverlays(context) }
    val batteryOk = remember(refresh) { batteryIgnored(context) }
    val brand = remember { brandGuideFor(Build.MANUFACTURER) ?: GENERIC }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("你好，我是灵犀", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "一位常驻耳边的语音伴侣。为了不被系统提前休眠，请按需完成下面的开启项" +
                    "（完成后会自动打勾，全部完成前也可以直接开始使用）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            GuideRow("麦克风", "听你说话（语音对话与常听模式）", micOk) {
                context.startActivity(Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }
            GuideRow("通知", "常驻待命通知（防止后台被杀）", notifyOk) {
                context.startActivity(Intent("android.settings.APP_NOTIFICATION_SETTINGS").putExtra("app_package", context.packageName))
            }
            GuideRow("悬浮窗", "耳语胶囊与对话卡片", overlayOk) {
                context.startActivity(Intent(SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            }
            GuideRow("电池优化豁免", "常听模式下保持活跃（强烈建议）", batteryOk) {
                runCatching {
                    context.startActivity(Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                }.onFailure {
                    context.startActivity(Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("厂商自启动白名单 · ${brand.label}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(brand.hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (brand.entryComponents.isNotEmpty()) {
                OutlinedButton(
                    onClick = { openBrandEntry(context, brand) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("打开「${brand.label}」自启设置") }
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = onComplete, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (micOk && notifyOk && overlayOk && batteryOk) "完成，开始使用" else "先这样，开始使用")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GuideRow(title: String, desc: String, ok: Boolean, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        if (ok) {
            Text("✓ 已开启", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        } else {
            OutlinedButton(onClick = onOpen, shape = RoundedCornerShape(50)) { Text("去开启") }
        }
    }
}

private fun hasMic(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

private fun batteryIgnored(context: Context): Boolean =
    (context.getSystemService(Activity.POWER_SERVICE) as? PowerManager)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true

/** 逐个尝试厂商自启管理入口；全部失败回落本应用详情页 */
private fun openBrandEntry(context: Context, brand: BrandGuide) {
    for (comp in brand.entryComponents) {
        val parts = comp.split("/")
        if (parts.size != 2) continue
        val intent = Intent().setComponent(ComponentName(parts[0], parts[1]))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
    runCatching {
        context.startActivity(
            Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
    }
}
