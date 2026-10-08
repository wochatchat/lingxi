package com.lingxi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lingxi.ui.MainScreen
import com.lingxi.ui.settings.ProviderSettingsScreen
import com.lingxi.ui.theme.LingXiTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LingXiTheme {
                // R1：两屏状态切换足够；R3 胶囊/对话卡片多起来后再上 navigation-compose
                var screen by remember { mutableStateOf(Screen.HOME) }
                when (screen) {
                    Screen.HOME -> MainScreen(onOpenProviders = { screen = Screen.PROVIDERS })
                    Screen.PROVIDERS -> ProviderSettingsScreen(onBack = { screen = Screen.HOME })
                }
            }
        }
    }
}

private enum class Screen { HOME, PROVIDERS }
