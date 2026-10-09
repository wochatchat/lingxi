package com.lingxi

import android.app.Application
import com.lingxi.data.SettingsRepository
import com.lingxi.service.FloatingCapsuleService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LingXiApp : Application() {

    @Inject lateinit var settings: SettingsRepository

    private val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )

    override fun onCreate() {
        super.onCreate()
        // R3 常驻形态开关：胶囊或常听任一开启 → 起前台服务；全关 → 停止
        appScope.launch {
            kotlinx.coroutines.flow.combine(
                settings.capsuleEnabled,
                settings.alwaysListenEnabled,
            ) { capsule, listen -> capsule || listen }
                .distinctUntilChanged()
                .collect { on ->
                    if (on) FloatingCapsuleService.start(this@LingXiApp)
                    else FloatingCapsuleService.stop(this@LingXiApp)
                }
        }
    }
}
