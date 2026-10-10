package com.lingxi.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings as SystemSettings
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingxi.data.SettingsRepository
import com.lingxi.data.assist.AuditEntry
import com.lingxi.data.assist.AuditLog
import com.lingxi.service.LingXiAccessibilityService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AssistUiState(
    val uiAssistEnabled: Boolean = false,
    val accessibilityOn: Boolean = false,
    val audits: List<AuditEntry> = emptyList(),
)

@HiltViewModel
class AssistViewModel @Inject constructor(
    @ApplicationContext private val app: Context,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(AssistUiState())
    val ui: StateFlow<AssistUiState> = _ui.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            settings.uiAssistEnabled.collect { refresh(it) }
        }
    }

    /** 刷新：总开关 + 系统无障碍状态 + 审计记录 */
    fun refresh(enabled: Boolean? = null) {
        viewModelScope.launch {
            val on = enabled ?: settings.uiAssistEnabled.first()
            _ui.value = AssistUiState(
                uiAssistEnabled = on,
                accessibilityOn = LingXiAccessibilityService.enabled,
                audits = AuditLog.entries(app),
            )
        }
    }

    fun setUiAssist(on: Boolean) = viewModelScope.launch { settings.setUiAssistEnabled(on) }

    fun clearAudit() = viewModelScope.launch {
        AuditLog.clear(app)
        refresh()
    }
}

/**
 * F9 UI 代操作（实验性，默认关）：
 * - 系统无障碍服务开关引导 + 软件总开关（两者都开才生效）
 * - 风险告知原文（PRD §16.4 要求）
 * - 操作审计日志
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistScreen(
    onBack: () -> Unit,
    vm: AssistViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current

    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("UI 代操作（实验）") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "开启后，灵犀可以读取当前屏幕文字（read_screen）、代点屏幕元素（click_ui）。" +
                    "代点操作执行前会逐项向你确认（GuardedIO：只写不碰，人按最后一键）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            // 风险告知（PRD §16.4 要求原文写入）
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "⚠ 风险告知：在第三方应用内使用辅助服务代为操作，可能违反该应用的用户协议，" +
                        "存在账号受限等风险。灵犀默认关闭此功能；开启后每类操作执行前需确认，并留有审计日志。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp),
                )
            }
            val a11yOn = ui.accessibilityOn
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("启用 UI 代操作", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (a11yOn) "系统无障碍服务：已开启" else "系统无障碍服务：未开启（开启总开关后需到系统设置打开）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = ui.uiAssistEnabled, onCheckedChange = vm::setUiAssist)
            }
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(Intent(SystemSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text(if (a11yOn) "系统无障碍设置（可关闭）" else "去系统设置开启无障碍服务") }

            Text("操作审计", style = MaterialTheme.typography.titleMedium)
            if (ui.audits.isEmpty()) {
                Text(
                    "暂无操作记录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TextButton(onClick = vm::clearAudit) { Text("清空审计日志") }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(ui.audits, key = { it.ts.toString() + it.action + it.detail }) { e ->
                        Column {
                            Text(
                                "${formatTs(e.ts)} · ${e.action} · ${e.detail} · ${if (e.success) "成功" else "失败"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTs(ts: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))
