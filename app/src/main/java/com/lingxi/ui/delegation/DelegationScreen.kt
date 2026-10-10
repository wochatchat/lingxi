package com.lingxi.ui.delegation

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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingxi.data.SettingsRepository
import com.lingxi.data.delegation.DelegationRepository
import com.lingxi.data.delegation.TaskKind
import com.lingxi.data.delegation.TaskStatus
import com.lingxi.data.delegation.TaskTime
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

data class DelegationUiState(
    val tasks: List<com.lingxi.data.delegation.DelegationTaskEntity> = emptyList(),
    val lastReport: String = "",
)

@HiltViewModel
class DelegationViewModel @Inject constructor(
    private val delegation: DelegationRepository,
    settings: SettingsRepository,
) : ViewModel() {

    val state = combine(delegation.observeAll(), settings.lastMorningReport) { tasks, report ->
        DelegationUiState(tasks, report)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DelegationUiState())

    fun pause(id: Long) = viewModelScope.launch { delegation.pause(id) }
    fun resume(id: Long) = viewModelScope.launch { delegation.resume(id) }
    fun cancel(id: Long) = viewModelScope.launch { delegation.cancel(id) }
}

/** 委托任务列表页（F13）：任务状态/暂停恢复/取消 + 晨报存档 */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
fun DelegationScreen(
    onBack: () -> Unit,
    vm: DelegationViewModel = hiltViewModel(),
) {
    val ui by vm.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("委托任务") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (ui.lastReport.isNotBlank()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("最近晨报", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                ui.lastReport,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (ui.tasks.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("还没有委托任务", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "对我说「每天下午 3 点提醒我打坐」或「盯着 XX 快递到了告诉我」试试",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(ui.tasks, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    onPause = { vm.pause(task.id) },
                    onResume = { vm.resume(task.id) },
                    onCancel = { vm.cancel(task.id) },
                )
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun TaskCard(
    task: com.lingxi.data.delegation.DelegationTaskEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(task.status)
            }
            val detail = when (task.kind) {
                TaskKind.REMINDER ->
                    "提醒 · " + TaskTime.formatAt(task.triggerAt, ZoneId.systemDefault())
                TaskKind.POLL -> "巡查 · 每${task.intervalMinutes}分钟"
                else -> task.kind
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (task.lastResult.isNotBlank()) {
                Text(
                    task.lastResult,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (task.status == TaskStatus.ACTIVE || task.status == TaskStatus.PAUSED) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (task.status == TaskStatus.ACTIVE) {
                        AssistChip(onClick = onPause, label = { Text("暂停") })
                    } else {
                        AssistChip(onClick = onResume, label = { Text("恢复") })
                    }
                    AssistChip(onClick = onCancel, label = { Text("取消") })
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun StatusBadge(status: String) {
    val (label, color) = when (status) {
        TaskStatus.ACTIVE -> "进行中" to MaterialTheme.colorScheme.primary
        TaskStatus.PAUSED -> "已暂停" to MaterialTheme.colorScheme.tertiary
        TaskStatus.DONE -> "已完成" to MaterialTheme.colorScheme.outline
        else -> "已取消" to MaterialTheme.colorScheme.outline
    }
    Text(label, style = MaterialTheme.typography.labelMedium, color = color)
}
