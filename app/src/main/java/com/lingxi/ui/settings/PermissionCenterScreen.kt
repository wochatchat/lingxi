package com.lingxi.ui.settings

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lingxi.data.permissions.PermissionCatalog
import com.lingxi.data.permissions.PermissionItem
import com.lingxi.data.permissions.PermissionKind
import com.lingxi.service.LingXiAccessibilityService

/**
 * 权限中心（R15）：全部权限清单 + 状态 + 一键跳系统设置。
 * 运行时权限在行内直接发起申请；特殊权限跳系统专项页。
 */
@Composable
fun PermissionCenterScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // 每次进页/onResume 刷一遍授权状态（key → granted）
    val granted = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(Unit) { refreshStatus(context, granted) }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { androidx.compose.material3.Text("权限中心") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                androidx.compose.material3.Text(
                    "灵犀用到这些权限：核心能力（麦克风/通知/悬浮窗）建议开启；其余按需开启，不用到的功能不申请。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(PermissionCatalog.CATALOG) { item ->
                val ok = granted[item.key] ?: false
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        androidx.compose.material3.Text(item.title, style = MaterialTheme.typography.titleMedium)
                        androidx.compose.material3.Text(
                            item.desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Badge(
                                containerColor = if (ok) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.errorContainer,
                            ) {
                                Text(if (ok) "已授权" else "未授权")
                            }
                            AssistChip(
                                onClick = { requestOrOpen(context, item) },
                                label = { Text(if (ok) "去管理" else "去授权") },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 行内「去授权」：运行时权限直接弹系统授权框；特殊权限跳对应系统页 */
private fun requestOrOpen(context: android.content.Context, item: com.lingxi.data.permissions.PermissionItem) {
    when (item.kind) {
        PermissionKind.RUNTIME -> runCatching {
            androidx.core.app.ActivityCompat.requestPermissions(
                context as android.app.Activity,
                arrayOf(item.androidPermission),
                1001,
            )
        }
        PermissionKind.OVERLAY -> context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        PermissionKind.BATTERY -> context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        PermissionKind.ACCESSIBILITY -> context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        PermissionKind.AUTOSTART -> context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** 刷新各权限授予状态（RUNTIME/OVERLAY/BATTERY/ACCESSIBILITY 可检测；AUTOSTART 不可测） */
private fun refreshStatus(context: android.content.Context, granted: androidx.compose.runtime.snapshots.SnapshotStateMap<String, Boolean>) {
    for (item in PermissionCatalog.CATALOG) {
        granted[item.key] = when (item.kind) {
            PermissionKind.RUNTIME -> ContextCompat.checkSelfPermission(
                context, item.androidPermission,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            PermissionKind.OVERLAY -> Settings.canDrawOverlays(context)
            PermissionKind.BATTERY -> {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
            }
            PermissionKind.ACCESSIBILITY -> LingXiAccessibilityService.enabled
            PermissionKind.AUTOSTART -> false
        }
    }
}
