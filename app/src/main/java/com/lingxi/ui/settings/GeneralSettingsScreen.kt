package com.lingxi.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings as SystemSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
    val morningReportEnabled: Boolean = false,
    val morningReportTime: String = "08:00",
    val morningReportCity: String = "",
    val calendarReminderEnabled: Boolean = false,
    val calendarLeadMinutes: Int = 15,
    /** F16 全离线模式 */
    val offlineMode: Boolean = false,
)

private data class BaseFlags(
    val capsule: Boolean = false,
    val listen: Boolean = false,
    val offline: Boolean = false,
)

private data class ProactiveConfig(
    val morningEnabled: Boolean = false,
    val morningTime: String = "08:00",
    val morningCity: String = "",
    val calendarEnabled: Boolean = false,
    val calendarLead: Int = 15,
)

@HiltViewModel
class GeneralSettingsViewModel @Inject constructor(
    application: android.app.Application,
    private val settings: SettingsRepository,
) : AndroidViewModel(application) {

    private val base = combine(
        settings.capsuleEnabled,
        settings.alwaysListenEnabled,
        settings.offlineModeEnabled,
    ) { c, l, o -> BaseFlags(c, l, o) }

    private val proactive = combine(
        settings.morningReportEnabled,
        settings.morningReportTime,
        settings.morningReportCity,
        settings.calendarReminderEnabled,
        settings.calendarLeadMinutes,
    ) { on, time, city, cal, lead ->
        ProactiveConfig(on, time, city, cal, lead)
    }

    val state = combine(base, proactive) { b, p ->
        GeneralUiState(
            capsuleEnabled = b.capsule,
            alwaysListenEnabled = b.listen,
            morningReportEnabled = p.morningEnabled,
            morningReportTime = p.morningTime,
            morningReportCity = p.morningCity,
            calendarReminderEnabled = p.calendarEnabled,
            calendarLeadMinutes = p.calendarLead,
            offlineMode = b.offline,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GeneralUiState())

    fun setCapsule(enabled: Boolean) = viewModelScope.launch { settings.setCapsuleEnabled(enabled) }
    fun setAlwaysListen(enabled: Boolean) = viewModelScope.launch { settings.setAlwaysListenEnabled(enabled) }
    fun setOfflineMode(enabled: Boolean) = viewModelScope.launch { settings.setOfflineMode(enabled) }
    fun setMorningReportEnabled(enabled: Boolean) = viewModelScope.launch { settings.setMorningReportEnabled(enabled) }
    fun setMorningReportTime(time: String) = viewModelScope.launch { settings.setMorningReportTime(time) }
    fun setMorningReportCity(city: String) = viewModelScope.launch { settings.setMorningReportCity(city) }
    fun setCalendarReminder(enabled: Boolean) = viewModelScope.launch { settings.setCalendarReminderEnabled(enabled) }
    fun setCalendarLeadMinutes(minutes: Int) = viewModelScope.launch { settings.setCalendarLeadMinutes(minutes) }
}

/**
 * 通用设置（R3）：悬浮胶囊 + 常听模式开关。
 * 胶囊需要「显示在其他应用上层」权限；常听需要麦克风权限（G0 之一）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneralSettingsScreen(
    onBack: () -> Unit,
    onOpenMemory: () -> Unit = {},
    onOpenPower: () -> Unit = {},
    onOpenAssist: () -> Unit = {},
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
            EntryRow(
                title = "记忆管理",
                subtitle = "三层记忆：会话滑窗 / 每日摘要 / 长期画像；对话里说「记住…」即可沉淀，可在记忆管理页查看编辑",
                onClick = onOpenMemory,
            )
            EntryRow(
                title = "功耗面板",
                subtitle = "电量与 24h 掉电统计；一键降档：息屏全停 / 提高 VAD 阈值",
                onClick = onOpenPower,
            )
            EntryRow(
                title = "UI 代操作（实验）",
                subtitle = "无障碍读屏 + 代点屏幕元素；默认关、逐项确认、审计可查",
                onClick = onOpenAssist,
            )
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
            ScreenshotQaSection(vm)
            OfflineModeSection(ui, vm)
            MorningReportSection(ui, vm)
            CalendarReminderSection(ui, vm)
        }
    }
}

/** F10 截屏问答：照片权限授权入口（读取相册最近截图做多模态问答） */
@Composable
private fun ScreenshotQaSection(vm: GeneralSettingsViewModel) {
    val context = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.READ_MEDIA_IMAGES,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    EntryRow(
        title = "截屏问答",
        subtitle = if (granted) "照片权限已授权。先系统截屏，再对我说「看看屏幕上这个」" else "需要照片权限读取最近截图；上传分析前会先向你确认",
        onClick = { if (!granted) permLauncher.launch(android.Manifest.permission.READ_MEDIA_IMAGES) },
    )
}

/** F16 全离线模式：系统动作仍可用，云端对话停用（端侧模型接入前的占位） */
@Composable
private fun OfflineModeSection(ui: GeneralUiState, vm: GeneralSettingsViewModel) {
    SwitchRow(
        title = "全离线模式",
        subtitle = "不联网：设闹钟、开应用、提醒等系统动作照常可用；云端 AI 对话与语音识别停用（端侧模型后续版本接入）",
        checked = ui.offlineMode,
        onCheckedChange = vm::setOfflineMode,
    )
}

/** F11 晨报：开关 + 时间 + 城市（天气源 Open-Meteo，无需 Key） */
@Composable
private fun MorningReportSection(ui: GeneralUiState, vm: GeneralSettingsViewModel) {
    SwitchRow(
        title = "晨报",
        subtitle = "每天在设定时间推送：天气 + 今日日程 + 委托任务进展（需通知权限）",
        checked = ui.morningReportEnabled,
        onCheckedChange = vm::setMorningReportEnabled,
    )
    if (ui.morningReportEnabled) {
        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ui.morningReportTime,
                onValueChange = { vm.setMorningReportTime(it) },
                label = { Text("时间（HH:mm）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ui.morningReportCity,
                onValueChange = { vm.setMorningReportCity(it) },
                label = { Text("城市（可选，用于天气，如「上海」）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** F12 日程提醒：开关 + 提前分钟数 + 日历权限 */
@Composable
private fun CalendarReminderSection(ui: GeneralUiState, vm: GeneralSettingsViewModel) {
    val context = LocalContext.current
    val calendarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.setCalendarReminder(true) }
    SwitchRow(
        title = "日程提醒",
        subtitle = "读系统日历，日程开始前提前提醒（语音/卡片）",
        checked = ui.calendarReminderEnabled,
        onCheckedChange = { on ->
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CALENDAR
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (on && !granted) calendarLauncher.launch(Manifest.permission.READ_CALENDAR)
            else vm.setCalendarReminder(on)
        },
    )
    if (ui.calendarReminderEnabled) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "提前提醒：${ui.calendarLeadMinutes} 分钟",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15, 30).forEach { minutes ->
                    FilterChip(
                        selected = ui.calendarLeadMinutes == minutes,
                        onClick = { vm.setCalendarLeadMinutes(minutes) },
                        label = { Text("$minutes 分钟") },
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
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
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
