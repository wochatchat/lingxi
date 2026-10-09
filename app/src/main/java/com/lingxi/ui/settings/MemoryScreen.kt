package com.lingxi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingxi.data.memory.DailySummary
import com.lingxi.data.memory.MemoryRepository
import com.lingxi.data.memory.ProfileEntryEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MemoryUiState(
    val profile: List<ProfileEntryEntity> = emptyList(),
    val summaries: List<DailySummary> = emptyList(),
)

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memory: MemoryRepository,
) : ViewModel() {

    val state = kotlinx.coroutines.flow.combine(
        memory.profileEntries,
        memory.summariesFlow(),
    ) { profile, summaries -> MemoryUiState(profile, summaries) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MemoryUiState())

    fun clearTurns() = viewModelScope.launch { memory.clearTurns() }
}

/**
 * F7 记忆管理页：三层记忆可见可编辑。
 * - 长期画像：开关/删除（语音说「记住…」沉淀的条目，关闭即不再注入）
 * - 每日摘要：滚动展示最近摘要
 * - 清空：对话记录 / 长期画像
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    vm: MemoryViewModel = hiltViewModel(),
) {
    val ui by vm.state.collectAsState()
    var confirmClear by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记忆管理") },
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
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "长期画像（说「记住…」即可沉淀，下一轮即生效）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (ui.profile.isEmpty()) {
                item {
                    Text(
                        "还没有画像记忆。对话里说「以后叫我阿哲」「以后结论先说」这类话，我会记住并长期遵守。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(ui.profile, key = { it.id }) { entry ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.content, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "语音沉淀 · " + if (entry.enabled) "生效中" else "已停用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = entry.enabled,
                            onCheckedChange = { vm.setProfileEnabled(entry.id, it) },
                        )
                        IconButton(onClick = { vm.deleteProfile(entry.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { confirmClear = "profile" }) { Text("清空画像") }
                    TextButton(onClick = { confirmClear = "turns" }) { Text("清空对话记录") }
                }
            }
            item {
                Text(
                    "每日摘要（自动滚动，保留最近 14 天）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (ui.summaries.isEmpty()) {
                item {
                    Text(
                        "还没有每日摘要。跨天使用后会自动把前一天对话压缩成要点。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(ui.summaries, key = { it.date }) { s ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(s.date, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text(s.summary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (confirmClear != null) {
        AlertDialog(
            onDismissRequest = { confirmClear = null },
            title = { Text(if (confirmClear == "turns") "清空对话记录？" else "清空长期画像？") },
            text = { Text("该操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    if (confirmClear == "turns") vm.clearTurns() else vm.clearProfile()
                    confirmClear = null
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = null }) { Text("取消") }
            },
        )
    }
}
