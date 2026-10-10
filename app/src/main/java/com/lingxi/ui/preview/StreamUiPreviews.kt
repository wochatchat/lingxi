package com.lingxi.ui.preview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lingxi.data.UiAction
import com.lingxi.ui.PendingUiRow
import com.lingxi.ui.TurnUiCards
import com.lingxi.ui.theme.LingXiTheme

/**
 * Stream-UI 七原语 @Preview 沙盒（R10）：
 * Android Studio 打开本文件即可离线目检所有原语的挂起态与快照态，不用真机跑对话。
 * 注意：PendingUiRow/TurnUiCards 是主页的 internal 组件，这里只换数据不换实现，
 * 保证预览与线上渲染永远一致。
 */
private val sampleChoice = UiAction(
    id = 1L,
    type = UiAction.UiType.ChoiceSheet,
    title = "找到多个匹配的应用",
    options = listOf("微信", "企业微信", "微信读书"),
)

private val sampleConfirm = UiAction(
    id = 2L,
    type = UiAction.UiType.ConfirmGate,
    title = "发送短信",
    body = "收件人 13800000000：快递已到，麻烦放前台",
)

private val sampleInfo = UiAction(
    id = 3L,
    type = UiAction.UiType.InfoCard,
    title = "闹钟已设置",
    body = "明早 7 点 · 站会",
)

private val sampleParams = UiAction(
    id = 4L,
    type = UiAction.UiType.ParamPanel,
    title = "确认巡查参数",
    body = "任务「盯 SF1234567890 签收」，可调整巡查间隔（最小 15 分钟）",
    confirmLabel = "按这个来",
    fields = listOf(
        UiAction.ParamField(
            key = "interval_minutes",
            label = "巡查间隔（分钟）",
            value = "60",
            hint = "15-1440，如 60 = 每小时",
        ),
    ),
)

private val sampleProgress = UiAction(
    id = 5L,
    type = UiAction.UiType.ProgressBar,
    title = "配置同步",
    body = "正在上传到云端",
    progress = 62,
)

private val sampleChart = UiAction(
    id = 6L,
    type = UiAction.UiType.MiniChart,
    title = "电量走势（最近 6 次采样）",
    values = listOf(88f, 74f, 63f, 55f, 41f, 33f),
)

private val sampleTakeover = UiAction(
    id = 7L,
    type = UiAction.UiType.TakeoverPrompt,
    title = "代发确认",
    body = "要按草稿把这条消息发出去吗？超时将转为草稿不执行",
    ttlMs = 10_000,
)

@Preview(name = "挂起态 · 七原语", showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun PendingPrimitivesPreview() {
    LingXiTheme {
        Surface {
            Column(Modifier.padding(12.dp)) {
                PendingRow(sampleChoice)
                PendingRow(sampleConfirm)
                PendingRow(sampleParams)
                PendingRow(sampleProgress)
                PendingRow(sampleChart)
                PendingRow(sampleTakeover)
            }
        }
    }
}

@Composable
private fun PendingRow(action: UiAction) {
    PendingUiRow(
        pending = action,
        onChoice = {},
        onConfirm = {},
        onParams = {},
    )
}

@Preview(name = "快照回放态 · InfoCard", showBackground = true, widthDp = 360)
@Composable
private fun SnapshotCardsPreview() {
    LingXiTheme {
        Surface {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("—— 历史快照回放 ——", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                TurnUiCards(
                    listOf(
                        sampleInfo,
                        sampleChoice.copy(resolvedIndex = 0, resolvedText = "微信"),
                        sampleConfirm.copy(resolvedText = "已发送（请在短信应用点发送）"),
                        sampleParams.copy(resolvedText = "巡查间隔（分钟）：45"),
                        sampleProgress.copy(progress = 100, body = "已完成"),
                        sampleTakeover.copy(resolvedText = "超时转草稿"),
                    ),
                )
            }
        }
    }
}

@Preview(name = "挂起态 · 单面板放大", showBackground = true, widthDp = 360)
@Composable
private fun ParamPanelSoloPreview() {
    LingXiTheme {
        Surface {
            PendingRow(sampleParams)
        }
    }
}
