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
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    /** R9 快递查询凭据（快递100），空 = 未配置（快递委托回落占位巡查） */
    val expressCustomer: String = "",
    val expressKey: String = "",
)

@HiltViewModel
class DelegationViewModel @Inject constructor(
    private val delegation: DelegationRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val state = combine(
        delegation.observeAll(),
        settings.lastMorningReport,
        settings.expressCustomer,
        settings.expressKey,
    ) { tasks, report, customer, key ->
        DelegationUiState(tasks, report, customer, key)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DelegationUiState())

    fun pause(id: Long) = viewModelScope.launch { delegation.pause(id) }
    fun resume(id: Long) = viewModelScope.launch { delegation.resume(id) }
    fun cancel(id: Long) = viewModelScope.launch { delegation.cancel(id) }

    /** R9：保存快递查询凭据 */
    fun saveExpressCredentials(customer: String, key: String) = viewModelScope.launch {
        settings.setExpressCustomer(customer)
        settings.setExpressKey(key)
    }
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
            // R9 F13：快递查询凭据（快递100 企业参数），未配置时快递委托只打卡
            item {
                ExpressConfigCard(
                    customer = ui.expressCustomer,
                    key = ui.expressKey,
                    onSave = vm::saveExpressCredentials,
                )
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

/** R9：快递查询凭据卡片（customer + key 保存到 DataStore，不进仓库不入日志） */
@androidx.compose.runtime.Composable
private fun ExpressConfigCard(
    customer: String,
    key: String,
    onSave: (String, String) -> Unit,
) {
    var customerText by remember(customer) { mutableStateOf(customer) }
    var keyText by remember(customer) { mutableStateOf(key) }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "快递查询（快递100）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (customer.isBlank()) "未配置：盯快递任务只做打卡巡查；配置后走快递100 真实跟踪"
                else "已配置真实快递跟踪",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = customerText,
                onValueChange = { customerText = it },
                label = { Text("customer") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = keyText,
                onValueChange = { keyText = it },
                label = { Text("key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            TextButton(
                onClick = { onSave(customerText, keyText) },
                modifier = Modifier.padding(top = 4.dp),
            ) { Text("保存") }
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
