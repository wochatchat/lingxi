package com.lingxi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 灵犀品牌主色：靛青（"一点通"的通透感），R3 后随主题定制能力扩展
private val LingXiPrimary = Color(0xFF3D5AFE)
private val LingXiSecondary = Color(0xFF00BFA5)

private val LightColors = lightColorScheme(
    primary = LingXiPrimary,
    secondary = LingXiSecondary,
)

private val DarkColors = darkColorScheme(
    primary = LingXiPrimary,
    secondary = LingXiSecondary,
)

@Composable
fun LingXiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
