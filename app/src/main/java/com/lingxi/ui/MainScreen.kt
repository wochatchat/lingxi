package com.lingxi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lingxi.R

data class ModuleEntry(val title: String, val subtitle: String, val milestone: String)

// 模块路线图占位：R1 起逐轮点亮
val PlannedModules = listOf(
    ModuleEntry("对话引擎", "多 Provider 配置中心 + 流式对话", "M1a"),
    ModuleEntry("常听管线", "G0/G1 门控 + VAD + 逃生通道", "M1b"),
    ModuleEntry("耳语胶囊", "悬浮状态胶囊 + 对话卡片", "M1c"),
    ModuleEntry("三层记忆", "会话 / 每日摘要 / 长期画像", "M2"),
    ModuleEntry("委托任务", "盯快递、盯日程，到了告诉你", "M2"),
    ModuleEntry("UI 代操作", "读屏代点（默认关，逐项授权）", "M3"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(com.lingxi.R.string.app_name)) })
        },
    ) { padding ->
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 24.dp))
            Text(
                text = "灵犀",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "无唤醒词 · 永远在听 · 一点通意",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 24.dp))
            PlannedModules.forEach { m ->
                Card(modifier = Modifier.padding(vertical = 6.dp)) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Text(m.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(m.subtitle, style = MaterialTheme.typography.bodySmall)
                        Text(m.milestone, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
