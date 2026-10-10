package com.lingxi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingxi.data.ProviderConfig
import com.lingxi.data.ProviderKind
import com.lingxi.data.ProviderRepository
import com.lingxi.data.maskKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProvidersViewModel @Inject constructor(
    private val repo: ProviderRepository,
) : ViewModel() {
    val providers = repo.providers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** F18 存储模式（true = AndroidKeyStore 加密；false = 降级普通 prefs） */
    val encryptedStorage = repo.isEncryptedStorage

    fun upsert(config: ProviderConfig, apiKey: String?) =
        viewModelScope.launch { repo.upsert(config, apiKey) }

    fun delete(id: String) = viewModelScope.launch { repo.delete(id) }

    fun setEnabled(id: String, enabled: Boolean) =
        viewModelScope.launch { repo.setEnabled(id, enabled) }

    fun test(config: ProviderConfig, apiKey: String, onResult: (Result<String>) -> Unit) =
        viewModelScope.launch { onResult(repo.testConnection(config, apiKey)) }

    fun apiKeyOf(id: String): String = repo.getApiKey(id)

    /** F18：清除全部 API Key（配置保留） */
    fun clearAllKeys(onDone: () -> Unit) =
        viewModelScope.launch { repo.clearAllKeys(); onDone() }

    /** F18：导出配置 JSON（不含 Key） */
    fun exportConfigs(onResult: (String) -> Unit) =
        viewModelScope.launch { onResult(repo.exportConfigsJson()) }
}

/** Provider 配置中心（F6）：云厂商 / 中转站 / 本地 Ollama 统一配置 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSettingsScreen(
    onBack: () -> Unit,
    viewModel: ProvidersViewModel = hiltViewModel(),
) {
    val providers by viewModel.providers.collectAsState()
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 服务 (Provider)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            androidx.compose.material3.FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "添加")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = "OpenAI-compatible 统一接口：云厂商、自定义中转站、本地 Ollama 一处配置。API Key 加密存于本机（F18）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            SecurityFooterSection(viewModel)
            Spacer(modifier = Modifier.height(8.dp))
            if (providers.isEmpty()) {
                Text(
                    "还没有配置。点右下角 + 添加第一个 AI 服务。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(providers, key = { it.id }) { p ->
                    ProviderCard(
                        config = p,
                        hasKey = viewModel.apiKeyOf(p.id).isNotBlank() || p.kind == ProviderKind.LOCAL,
                        onEdit = { editing = p },
                        onDelete = { viewModel.delete(p.id) },
                        onToggle = { viewModel.setEnabled(p.id, it) },
                    )
                }
            }
        }
    }

    if (showAdd || editing != null) {
        ProviderEditDialog(
            existing = editing,
            viewModel = viewModel,
            onDismiss = { showAdd = false; editing = null },
        )
    }
}

/** F18 收尾：存储模式展示 + 导出配置（不含 Key）+ 清除全部 Key */
@Composable
private fun SecurityFooterSection(viewModel: ProvidersViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (viewModel.encryptedStorage) "🔒 Key 存储：AndroidKeyStore 加密" else "⚠ Key 存储：加密初始化失败，已降级普通 prefs（仅本机私有目录）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.OutlinedButton(onClick = {
            viewModel.exportConfigs { json ->
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("lingxi_providers", json))
                notice = "配置已复制到剪贴板（不含 API Key）"
            }
        }) { Text("导出配置（不含 Key）") }
        androidx.compose.material3.OutlinedButton(onClick = { confirmClear = true }) {
            Text("清除全部 Key", color = MaterialTheme.colorScheme.error)
        }
    }
    notice?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除全部 API Key") },
            text = { Text("将删除本机存储的所有 API Key（服务配置保留，重新填 Key 即可使用）。确定继续？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearAllKeys { notice = "已清除全部 API Key" }
                }) { Text("清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ProviderCard(
    config: ProviderConfig,
    hasKey: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(config.name, fontWeight = FontWeight.SemiBold)
                Text(
                    "${kindLabel(config.kind)} · ${config.model}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (hasKey) "Key: 已配置" else "Key: 未配置",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            Switch(checked = config.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onEdit) {
                Text("编辑", style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除")
            }
        }
    }
}

private fun kindLabel(kind: ProviderKind): String = when (kind) {
    ProviderKind.CLOUD -> "云厂商"
    ProviderKind.RELAY -> "中转站"
    ProviderKind.LOCAL -> "本地"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderEditDialog(
    existing: ProviderConfig?,
    viewModel: ProvidersViewModel,
    onDismiss: () -> Unit,
) {
    // 预设选择（仅新增时展示）；编辑时字段独立
    var selectedPresetKey by remember { mutableStateOf<String?>(null) }
    val initial = existing
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var apiKey by remember { mutableStateOf(initial?.let { viewModel.apiKeyOf(it.id) } ?: "") }
    var kind by remember { mutableStateOf(initial?.kind ?: ProviderKind.CLOUD) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    fun applyPreset(key: String) {
        selectedPresetKey = key
        val preset = com.lingxi.data.PROVIDER_PRESETS.firstOrNull { it.key == key }
            ?: com.lingxi.data.CUSTOM_PRESET
        name = if (preset.key == "custom") "" else preset.label
        baseUrl = preset.baseUrl
        model = preset.model
        kind = preset.kind
        testResult = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "添加 AI 服务" else "编辑 ${existing.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (existing == null) {
                    Text("选预设：", style = MaterialTheme.typography.labelMedium)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // 第一行：云厂商常用
                        listOf("deepseek", "kimi", "qwen", "openai").forEach { key ->
                            FilterChip(
                                selected = selectedPresetKey == key,
                                onClick = { applyPreset(key) },
                                label = { Text(presetLabel(key)) },
                            )
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        listOf("zhipu", "doubao", "ollama", "custom").forEach { key ->
                            FilterChip(
                                selected = selectedPresetKey == key,
                                onClick = { applyPreset(key) },
                                label = { Text(presetLabel(key)) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; testResult = null },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://your-gateway.com/v1") },
                    supportingText = { Text("OpenAI-compatible 根路径，需含 /v1 等版本段") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (kind != ProviderKind.LOCAL) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        supportingText = { Text(if (apiKey.isBlank()) "" else maskKey(apiKey)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                testResult?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("✓")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank() && !testing,
                onClick = {
                    testing = true
                    testResult = null
                    val config = com.lingxi.data.ProviderConfig(
                        id = existing?.id ?: com.lingxi.data.newProviderId(),
                        name = name.trim(),
                        kind = kind,
                        baseUrl = com.lingxi.data.normalizeBaseUrl(baseUrl),
                        model = model.trim(),
                        enabled = existing?.enabled ?: true,
                    )
                    viewModel.upsert(config, apiKey.trim().takeIf { it.isNotBlank() })
                    viewModel.test(config, apiKey.trim()) { r ->
                        testing = false
                        testResult = r.fold(
                            onSuccess = { "✓ 连接成功：$it" },
                            onFailure = { "✗ ${it.message}" },
                        )
                    }
                },
            ) {
                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("测试连接")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (existing == null) "取消" else "完成") }
        },
    )
}

private fun presetLabel(key: String): String =
    (com.lingxi.data.PROVIDER_PRESETS + com.lingxi.data.CUSTOM_PRESET)
        .firstOrNull { it.key == key }?.label ?: key
