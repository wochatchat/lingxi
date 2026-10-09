package com.lingxi.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lingxi.R
import com.lingxi.data.ConvState
import com.lingxi.data.ConvTurn

/**
 * 对话主页（R2）：按住说话（push-to-talk）+ 文本兜底 + 流式状态展示。
 */
@Composable
fun MainScreen(
    onOpenProviders: () -> Unit = {},
    onOpenGeneral: () -> Unit = {},
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
