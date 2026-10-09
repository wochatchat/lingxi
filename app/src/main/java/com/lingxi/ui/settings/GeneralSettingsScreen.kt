package com.lingxi.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings as SystemSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lingxi.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GeneralUiState(
    val capsuleEnabled: Boolean = false,
    val alwaysListenEnabled: Boolean = false,
)

@HiltViewModel
class GeneralSettingsViewModel @Inject constructor(
    application: android.app.Application,
    private val settings: SettingsRepository,
) : AndroidViewModel(application) {

    val state = combine(
        settings.capsuleEnabled,
        settings.alwaysListenEnabled,
    ) { c, l -> GeneralUiState(c, l) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GeneralUiState())

    fun setCapsule(enabled: Boolean) = viewModelScope.launch { settings.setCapsuleEnabled(enabled) }
    fun setAlwaysListen(enabled: Boolean) = viewModelScope.launch { settings.setAlwaysListenEnabled(enabled) }
}

/**
 * 通用设置（R3）：悬浮胶囊 + 常听模式开关。
 * 胶囊需要「显示在其他应用上层」权限；常听需要麦克风权限（G0 之一）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(
    onBack: () -> Unit,
    vm: GeneralSettingsViewModel = hiltViewModel(),
) {
    val ui by vm.state.collectAsState()
    val context = LocalContext.current
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> vm.setAlwaysListen(granted) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通用设置") },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SwitchRow(
                title = "悬浮胶囊",
                subtitle = "屏幕边缘常驻小胶囊，点击展开对话卡片；胶囊颜色跟随灵犀状态变化",
                checked = ui.capsuleEnabled,
                onCheckedChange = { on ->
                    if (on && !SystemSettings.canDrawOverlays(context)) {
                        context.startActivity(
                            Intent(
                                SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    } else {
                        vm.setCapsule(on)
                    }
                },
            )
            SwitchRow(
                title = "常听模式",
                subtitle = "持续聆听，检测到说话自动应答（G0 服务开关 + G1 能量门控）；播报期间自动静默防回声",
                checked = ui.alwaysListenEnabled,
                onCheckedChange = { on ->
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (on && !granted) micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    else vm.setAlwaysListen(on)
                },
            )
            Text(
                text = "提示：常听模式依赖所配置 AI 服务的语音识别能力；" +
                    "来不及等它反应时，可在卡片上按住麦克风直接说话（按键说话逃生）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
