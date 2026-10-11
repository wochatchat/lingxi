package com.lingxi.data.behavior

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lingxi.data.behavior.BehaviorSuggestEntryPoint
import com.lingxi.data.SettingsRepository
import com.lingxi.data.memory.BehaviorPredictor
import com.lingxi.data.memory.MemoryRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * R13 行为预测建议通知 worker（周期 2h，轻量）：
 * 总开关（行为预测）+ 通知开关都开启时，算当前时段的建议，
 * 置信度够高且当天没推过该动作 → 推一条「现在可以」通知。
 * 每动作每天最多一条（本地 prefs 去重），防骚扰。
 */
class SuggestionWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, BehaviorSuggestEntryPoint::class.java)
        val settings = entry.settings()
        val on = runCatching {
            settings.behaviorSuggestEnabled.first() && settings.suggestionNotifyEnabled.first()
        }.getOrDefault(false)
        if (!on) return Result.success()

        val suggestions = runCatching { entry.memory().suggestions() }.getOrNull().orEmpty()
        val top = suggestions.firstOrNull() ?: return Result.success()
        if (top.confidence < BehaviorPredictor.NOTIFY_CONFIDENCE) return Result.success()

        val prefs = prefs(applicationContext)
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
            .format(System.currentTimeMillis())
        val key = "${today}_${top.action}"
        if (prefs.getString(KEY_LAST_NOTIFIED, null) == key) return Result.success()
        prefs.edit().putString(KEY_LAST_NOTIFIED, key).apply()

        entry.notifier().post(
            "现在可以：${top.label}",
            "灵犀注意到你常在这个时间「${top.label}」，点按这里直接开始（可在通用设置关闭）。",
        )
        return Result.success()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("suggestion_notify", Context.MODE_PRIVATE)

    companion object {
        private const val WORK_NAME = "lingxi-suggestion"
        private const val KEY_LAST_NOTIFIED = "last_suggestion_notify"

        /** 建议通知开启期间 2h 一查；关闭即取消（入口在 LingXiApp 设置收集） */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (enabled) {
                val request = PeriodicWorkRequestBuilder<SuggestionWorker>(2, TimeUnit.HOURS).build()
                wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                wm.cancelUniqueWork(WORK_NAME)
            }
        }
    }
}
