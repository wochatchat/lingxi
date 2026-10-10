package com.lingxi.ui.settings

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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingxi.data.SettingsRepository
import com.lingxi.data.power.BatterySample
import com.lingxi.data.power.PowerStats
import com.lingxi.data.power.PowerStatsCalculator
import com.lingxi.data.power.PowerStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PowerUiState(
    val battery: BatterySample? = null,
    val stats: PowerStats = PowerStats(null, null, null, "加载中…"),
    val alwaysListen: Boolean = false,
    val capsule: Boolean = false,
    val vadThreshold: Int = 500,
    val screenOffStop: Boolean = false,
)

@HiltViewModel
class PowerPanelViewModel @Inject constructor(
    @ApplicationContext private val app: Context,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(PowerUiState())
    val ui: StateFlow<PowerUiState> = _ui.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            combine(
                settings.alwaysListenEnabled,
                settings.capsuleEnabled,
                settings.vadThreshold,
                settings.screenOffStopEnabled,
            ) { listen, capsule, vad, stop ->
                PowerUiState(
                    battery = _ui.value.battery,
                    stats = _ui.value.stats,
                    alwaysListen = listen,
                    capsule = capsule,
                    vadThreshold = vad,
                    screenOffStop = stop,
                )
            }.collect { _ui.value = it.copy(battery = _ui.value.battery, stats = _ui.value.stats) }
        }
    }

    /** 刷新电量读数与 24h 统计（进面板/下拉时调用） */
    fun refresh() {
        viewModelScope.launch {
            val current = PowerStore.current(app)
            val samples = PowerStore.load(app)
            val stats = PowerStatsCalculator.compute(samples, System.currentTimeMillis())
            _ui.value = _ui.value.copy(battery = current, stats = stats)
        }
    }

    fun setVadThreshold(v: Int) = viewModelScope.launch { settings.setVadThreshold(v) }
    fun setScreenOffStop(on: Boolean) = viewModelScope.launch { settings.setScreenOffStopEnabled(on) }
    fun setAlwaysListen(on: Boolean) = viewModelScope.launch { settings.setAlwaysListenEnabled(on) }
}

/**
 * F17 功耗面板：当前电量 + 24h 掉电统计 + 常听管线降档（息屏全停 / 提高 VAD 阈值）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerPanelScreen(
    onBack: () -> Unit,
    vm: PowerPanelViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()

    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("功耗面板") },
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
            // 电量与 24h 统计
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val level = ui.battery?.level
                    Text(
                        text = when {
                            level == null -> "电量：--（暂无采样）"
                            ui.battery.charging == true -> "🔋 $level%（充电中）"
                            else -> "🔋 $level%"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    val drop = ui.stats.drop24h
                    Text(
                        text = when {
                            drop == null -> "24h 掉电：--（${ui.stats.note}）"
                            drop <= 0 -> "24h 掉电：基本持平（${ui.stats.note}）"
                            else -> "24h 掉电：约 $drop%（${ui.stats.note}）"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "统计来自 App 运行期间的电量采样（进程被杀即暂停，重启后继续累计）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 常听管线占用状态
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("常听管线", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = buildString {
                            append(if (ui.alwaysListen) "常听：开启中（VAD 门控 + 云 ASR）" else "常听：已关闭")
                            if (ui.capsule) append("；悬浮胶囊：运行中") 
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "VAD 灵敏度（阈值越高越难触发，越省电）：当前 ${ui.vadThreshold}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(500 to "标准", 800 to "省电", 1200 to "严格").forEach { (v, label) ->
                            FilterChip(
                                selected = ui.vadThreshold == v,
                                onClick = { vm.setVadThreshold(v) },
                                label = { Text("$label ($v)") },
                            )
                        }
                    }
                }
            }

            // 一键降档
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("一键降档", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("息屏全停", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "息屏时暂停常听麦克风，亮屏自动恢复",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = ui.screenOffStop, onCheckedChange = vm::setScreenOffStop)
                    }
                }
            }
        }
    }
}
