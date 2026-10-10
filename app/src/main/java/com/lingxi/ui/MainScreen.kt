package com.lingxi.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lingxi.R
import com.lingxi.data.ConvState
import com.lingxi.data.ConvTurn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.lingxi.data.UiAction

/**
 * 对话主页（R2）：按住说话（push-to-talk）+ 文本兜底 + 流式状态展示。
 */
@Composable
fun MainScreen(
    onOpenProviders: () -> Unit = {},
    onOpenGeneral: () -> Unit = {},
    onOpenDelegations: () -> Unit = {},
    vm: ConversationViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsState()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> vm.onMicPermission(granted) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenDelegations) {
                        Icon(Icons.Filled.TaskAlt, contentDescription = "委托任务")
                    }
                    IconButton(onClick = onOpenGeneral) {
                        Icon(Icons.Filled.Tune, contentDescription = "通用设置")
                    }
                    IconButton(onClick = onOpenProviders) {
                        Icon(Icons.Filled.Settings, contentDescription = "AI 服务配置")
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
        ) {
            ConvHistory(history = ui.history, modifier = Modifier.weight(1f))
            PendingUiRow(
                pending = ui.pending,
                onChoice = vm::onUiChoice,
                onConfirm = vm::onUiConfirm,
                onParams = vm::onUiParams,
            )
            StateLine(ui.engineState)
            ConvInputBar(
                recording = ui.recording,
                micGranted = ui.micGranted,
                onStart = vm::startListening,
                onStop = vm::stopListening,
                onSend = vm::sendText,
                onRequestMic = {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
            )
        }
    }
}

@Composable
private fun ConvHistory(history: List<ConvTurn>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(history.size) {
        if (history.isNotEmpty()) listState.animateScrollToItem(history.lastIndex)
    }
    if (history.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("灵犀", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(
                "按住下方麦克风说话，或直接打字",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(history) { turn ->
            Bubble(text = turn.user, mine = true)
            Bubble(text = turn.reply.ifBlank { "（没有回答）" }, mine = false)
            TurnUiCards(turn.ui)
        }
    }
}

/** 历史轮次上挂靠的 Stream-UI 卡片（状态即快照，可回放；internal 供 @Preview 沙盒复用） */
@Composable
internal fun TurnUiCards(actions: List<UiAction>) {
    for (action in actions) {
        when (action.type) {
            UiAction.UiType.ChoiceSheet -> Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(action.title, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        action.resolvedText
                            ?: action.options.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("　"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp).widthIn(max = 300.dp),
                    )
                }
            }
            UiAction.UiType.ConfirmGate -> Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("🔔 ${action.title}", style = MaterialTheme.typography.labelMedium)
                    action.body?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp).widthIn(max = 300.dp))
                    }
                    action.resolvedText?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            UiAction.UiType.InfoCard -> Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("✓ ${action.title}", style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold)
                    action.body?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp).widthIn(max = 300.dp))
                    }
                }
            }
            // R9 快照渲染：ParamPanel / ProgressBar / MiniChart / TakeoverPrompt
            UiAction.UiType.ParamPanel -> Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("⚙ ${action.title}", style = MaterialTheme.typography.labelMedium)
                    Text(
                        action.resolvedText
                            ?: action.fields.joinToString("；") { "${it.label}：${it.value}" },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp).widthIn(max = 300.dp),
                    )
                }
            }
            UiAction.UiType.ProgressBar -> Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Column(Modifier.padding(10.dp).widthIn(max = 300.dp)) {
                    Text("⏳ ${action.title}", style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(
                        progress = { if (action.progress in 0..100) action.progress / 100f else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    action.body?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            UiAction.UiType.MiniChart -> Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("📈 ${action.title}", style = MaterialTheme.typography.labelMedium)
                    Sparkline(
                        values = action.values,
                        modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 6.dp),
                    )
                    action.body?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp).widthIn(max = 300.dp))
                    }
                }
            }
            UiAction.UiType.TakeoverPrompt -> Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("🤝 ${action.title}", style = MaterialTheme.typography.labelMedium)
                    action.body?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp).widthIn(max = 300.dp))
                    }
                    action.resolvedText?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

/**
 * 挂起中的 UI 原语（本回合等待用户应答）：ChoiceSheet chips / ConfirmGate 按钮 /
 * ParamPanel 表单 / ProgressBar / MiniChart / TakeoverPrompt 倒计时。
 * 语音通道等价：说"第 N 个"或"确认/取消"同样生效（engine 解析）。
 */
@Composable
internal fun PendingUiRow(
    pending: UiAction?,
    onChoice: (Int) -> Unit,
    onConfirm: (Boolean) -> Unit,
    onParams: (String) -> Unit = {},
) {
    val p = pending ?: return
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(p.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            p.body?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp))
            }
            when (p.type) {
                UiAction.UiType.ChoiceSheet -> {
                    p.options.forEachIndexed { i, opt ->
                        Text(
                            "第${i + 1}个 · $opt",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable { onChoice(i) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                    Text("点选上方选项，或说\"第 N 个\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp))
                }
                UiAction.UiType.ConfirmGate -> {
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(onClick = { onConfirm(false) }) { Text(p.cancelLabel) }
                        TextButton(onClick = { onConfirm(true) }) { Text(p.confirmLabel) }
                    }
                }
                // R9 ParamPanel：可编辑参数表单，确认提交 JSON；语音"确认"=默认参数
                UiAction.UiType.ParamPanel -> ParamForm(p, onParams, onConfirm)
                // R9 ProgressBar / MiniChart：非打断展示，无应答
                UiAction.UiType.ProgressBar -> {
                    LinearProgressIndicator(
                        progress = { if (p.progress in 0..100) p.progress / 100f else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text(
                        if (p.progress >= 0) "${p.progress}%" else "进行中",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                }
                UiAction.UiType.MiniChart -> {
                    Sparkline(
                        values = p.values,
                        modifier = Modifier.fillMaxWidth().height(56.dp).padding(top = 8.dp),
                    )
                    Text("最近走势", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                }
                // R9 TakeoverPrompt：全模态限时，倒计时归零由引擎超时自动转草稿
                UiAction.UiType.TakeoverPrompt -> {
                    var remainSec by remember(p.id) {
                        mutableIntStateOf(kotlin.math.ceil(p.ttlMs / 1000.0).toInt().coerceAtLeast(1))
                    }
                    LaunchedEffect(p.id) {
                        while (remainSec > 0) {
                            kotlinx.coroutines.delay(1000)
                            remainSec--
                        }
                    }
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { onConfirm(false) }) { Text(p.cancelLabel) }
                        TextButton(onClick = { onConfirm(true) }) { Text(p.confirmLabel) }
                        Text("剩 ${remainSec}s 超时转草稿",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                UiAction.UiType.InfoCard -> Unit
            }
        }
    }
}

/** ParamPanel 表单：每个字段一个输入框，确认提交 {"key":"value"} JSON */
@Composable
private fun ParamForm(
    action: UiAction,
    onParams: (String) -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    val values = remember(action.id) { mutableStateMapOf<String, String>() }
    action.fields.forEach { f ->
        OutlinedTextField(
            value = values[f.key] ?: f.value,
            onValueChange = { values[f.key] = it },
            label = { Text(f.label) },
            placeholder = { Text(f.hint) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = { onConfirm(false) }) { Text(action.cancelLabel) }
        TextButton(onClick = {
            // 值未改动的字段不提交（回落默认值语义）
            val changed = action.fields
                .mapNotNull { f -> values[f.key]?.takeIf { it != f.value }?.let { f.key to it } }
                .toMap()
            if (changed.isEmpty()) {
                onConfirm(true)
            } else {
                val obj = changed.mapValues { (_, v) -> JsonPrimitive(v) }
                onParams(JsonObject(obj).toString())
            }
        }) { Text(action.confirmLabel) }
    }
}

/** 迷你折线图（Canvas sparkline，MiniChart 用，非打断） */
@Composable
private fun Sparkline(values: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (values.size < 2) {
            drawLine(
                color = Color.Gray,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = 3f,
            )
            return@Canvas
        }
        val minV = values.min()
        val maxV = values.max()
        val range = (maxV - minV).takeIf { it > 0f } ?: 1f
        val stepX = size.width / (values.size - 1)
        val points = values.mapIndexed { i, v ->
            Offset(i * stepX, size.height * (1f - (v - minV) / range) * 0.9f + size.height * 0.05f)
        }
        for (i in 0 until points.size - 1) {
            drawLine(
                color = Color(0xFF5B8DEF),
                start = points[i],
                end = points[i + 1],
                strokeWidth = 4f,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Text(
                text = text,
                modifier = Modifier
                    .padding(12.dp)
                    .widthIn(max = 300.dp),
            )
        }
    }
}

@Composable
private fun StateLine(state: ConvState) {
    val text = when (state) {
        is ConvState.Transcribing -> "正在识别…"
        is ConvState.Thinking -> if (state.partial.isBlank()) "思考中…" else state.partial
        is ConvState.Speaking -> "播报中…"
        is ConvState.Failed -> "⚠ ${state.message}"
        else -> ""
    }
    if (text.isEmpty()) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (state is ConvState.Failed) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/**
 * 底部输入栏：按住说话按钮 + 文本框 + 发送。
 * 未授权麦克风时按麦克风按钮先走权限申请。
 */
@Composable
private fun ConvInputBar(
    recording: Boolean,
    micGranted: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
    onRequestMic: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(
                    if (recording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
                .pointerInput(micGranted) {
                    awaitEachGesture {
                        awaitFirstDown()
                        if (!micGranted) {
                            onRequestMic()
                            // 等待本手势结束，避免下一次误触发
                            waitForUpOrCancellation()
                        } else {
                            onStart()
                            waitForUpOrCancellation()
                            onStop()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = "按住说话",
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            placeholder = { Text("说点什么…") },
            maxLines = 3,
        )
        IconButton(
            onClick = {
                onSend(draft)
                draft = ""
            },
            enabled = draft.isNotBlank(),
        ) {
            Icon(Icons.Filled.Send, contentDescription = "发送")
        }
    }
}
