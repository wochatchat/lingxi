package com.lingxi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.lingxi.data.SettingsRepository
import com.lingxi.ui.MainScreen
import com.lingxi.ui.onboarding.FirstRunGuideScreen
import com.lingxi.ui.settings.GeneralSettingsScreen
import com.lingxi.ui.settings.MemoryScreen
import com.lingxi.ui.settings.ProviderSettingsScreen
import com.lingxi.ui.theme.LingXiTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LingXiTheme {
                // R1：状态切换足够；R4 胶囊/对话卡片多起来后再上 navigation-compose
                RootRoute(settings)
            }
        }
    }
}

private enum class Screen { LOADING, ONBOARD, HOME, PROVIDERS, GENERAL, MEMORY, DELEGATIONS }

/** 根路由：F15 首启（未完成引导）先进引导屏，完成后进主页 */
@Composable
private fun RootRoute(settings: SettingsRepository) {
    var screen by remember { mutableStateOf(Screen.LOADING) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (!settings.firstLaunchDone.first()) screen = Screen.ONBOARD
        else if (screen == Screen.LOADING) screen = Screen.HOME
    }
    when (screen) {
        Screen.LOADING -> Unit
        Screen.ONBOARD -> FirstRunGuideScreen(
            onComplete = {
                screen = Screen.HOME
                scope.launch { settings.setFirstLaunchDone(true) }
            },
        )
        Screen.HOME -> MainScreen(
            onOpenProviders = { screen = Screen.PROVIDERS },
            onOpenGeneral = { screen = Screen.GENERAL },
            onOpenDelegations = { screen = Screen.DELEGATIONS },
        )
        Screen.PROVIDERS -> ProviderSettingsScreen(onBack = { screen = Screen.HOME })
        Screen.GENERAL -> GeneralSettingsScreen(
            onBack = { screen = Screen.HOME },
            onOpenMemory = { screen = Screen.MEMORY },
        )
        Screen.DELEGATIONS -> com.lingxi.ui.delegation.DelegationScreen(onBack = { screen = Screen.HOME })
        Screen.MEMORY -> MemoryScreen(onBack = { screen = Screen.HOME })
    }
}
