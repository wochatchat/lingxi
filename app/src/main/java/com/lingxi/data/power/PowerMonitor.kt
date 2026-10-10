package com.lingxi.data.power

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 一条电量采样（App 进程存活期间由 ACTION_BATTERY_CHANGED 广播落账） */
@Serializable
data class BatterySample(
    val ts: Long,
    /** 0-100 */
    val level: Int,
    val charging: Boolean,
)

/** 功耗面板统计结果 */
data class PowerStats(
    /** 当前电量（最近一条样本），null = 还没有采样 */
    val currentLevel: Int?,
    val charging: Boolean?,
    /** 24h 掉电百分点（正数=掉电；null = 数据不足或被充电干扰） */
    val drop24h: Int?,
    /** 统计可信度说明（UI 直接展示） */
    val note: String,
)

/** 24h 掉电统计（纯函数，可单测） */
object PowerStatsCalculator {

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** 基准样本与最新样本至少间隔这么久才算有效统计（避免刚装 App 就显示误导数据） */
    private const val MIN_SPAN_MS = 6 * 60 * 60 * 1000L

    /**
     * 掉电估算：取最接近 24h 前的样本为基准，与最新一条（两端都必须是非充电态）相减。
     * 数据不足 / 任一端在充电 → drop24h = null。
     */
    fun compute(samples: List<BatterySample>, now: Long): PowerStats {
        if (samples.isEmpty()) {
            return PowerStats(null, null, null, "暂无数据：App 运行期间会自动采样电量")
        }
        val sorted = samples.sortedBy { it.ts }
        val last = sorted.last()
        val cut = now - DAY_MS
        val ref = sorted.filter { it.ts < last.ts }
            .minByOrNull { kotlin.math.abs(it.ts - cut) }
        val spanOk = ref != null && last.ts - ref.ts >= MIN_SPAN_MS
        val drop = if (spanOk && !ref.charging && !last.charging) ref.level - last.level else null
        val note = when {
            !spanOk -> "采样不足 6 小时，仅供参考"
            ref.charging || last.charging -> "统计区间含充电时段，已剔除"
            else -> "过去 24 小时"
        }
        return PowerStats(last.level, last.charging, drop, note)
    }
}

/**
 * 电量采样存储（F17 功耗面板数据源）。
 * App 进程存活期间由 LingXiApp 的 BATTERY_CHANGED 广播写入；48h 滚动保留，上限 400 条。
 */
object PowerStore {

    private const val PREFS_NAME = "power_samples"
    private const val KEY = "samples_json"
    private const val RETAIN_MS = 48 * 60 * 60 * 1000L
    private const val MAX_SAMPLES = 400
    private const val MIN_GAP_MS = 15 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(BatterySample.serializer())

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): List<BatterySample> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString(listSerializer, raw) }.getOrDefault(emptyList())
    }

    /** 电量/充电态变化或超过 15 分钟才记一条；写入时清理 48h 旧样本 */
    @Synchronized
    fun addSample(context: Context, level: Int, charging: Boolean, now: Long) {
        val samples = load(context)
        val last = samples.lastOrNull()
        if (last != null && last.level == level && last.charging == charging && now - last.ts < MIN_GAP_MS) return
        val next = (samples + BatterySample(now, level, charging))
            .filter { it.ts >= now - RETAIN_MS }
            .takeLast(MAX_SAMPLES)
        prefs(context).edit().putString(KEY, json.encodeToString(listSerializer, next)).apply()
    }

    fun clear(context: Context) = prefs(context).edit().remove(KEY).apply()

    /** 读当前电量（sticky 广播，无需常驻 receiver） */
    fun current(context: Context): BatterySample? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return BatterySample(System.currentTimeMillis(), level * 100 / scale, charging)
    }
}
