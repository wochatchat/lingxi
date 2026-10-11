package com.lingxi.ui

import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.lingxi.data.UiAction

/**
 * R14 generative_html 富卡片：单文件 HTML 渲染在 WebView 内。
 *
 * 性能与安全约束：
 * - 仅在卡片出现在组合中时创建 WebView，移出组合即销毁（DisposableEffect）；
 * - 禁文件/内容访问；网络由消毒层剥除 + 注入的 CSP meta 兜底（default-src 'none'）。
 */
@Composable
fun GenerativeHtmlCard(action: UiAction, modifier: Modifier = Modifier) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.padding(start = 4.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                "🌐 ${action.title}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            val surfaceArgb = MaterialTheme.colorScheme.surfaceVariant.toArgb()
            val html = remember(action.id) { action.html }
            var webView by remember { mutableStateOf<WebView?>(null) }
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                        setBackgroundColor(surfaceArgb)
                        loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                        webView = this
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            // 性能约束：只在卡片出现时创建，移出组合即销毁
            DisposableEffect(action.id) {
                onDispose {
                    webView?.apply {
                        loadUrl("about:blank")
                        destroy()
                    }
                    webView = null
                }
            }
        }
    }
}
