package com.lingxi.data.delegation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F13 航班跟踪（R10）：AviationStack 实时航班接口（api.aviationstack.com）。
 * 需要 access_key（免费档即可），走设置页配置；未配置时 check 返回 null
 * （任务回落打卡巡查，不炸链路——与快递100 同一套降级策略）。
 */
object FlightTracking {

    private val FLIGHT_NO = Regex("""(?<![A-Za-z0-9])([A-Za-z]{2}\d{3,4})(?![0-9A-Za-z])""")

    /** 从任务标题/口述文本里提取航班号：两字母航司码 + 3-4 位数字（如 CA1234 / mu5678）。 */
    fun extractFlightNo(text: String): String? =
        FLIGHT_NO.find(text.replace("　", " "))?.groupValues?.get(1)?.uppercase()

    /** 航班号是否合法（参数校验用） */
    fun isFlightNo(s: String): Boolean = FLIGHT_NO.matches(s.trim())

    /**
     * 解析 AviationStack /v1/flights 响应 → 一句话状态摘要（纯函数，可单测）。
     * 响应形如 {"data":[{"flight_status":"active","flight":{"iata":"CA1234"},
     *   "departure":{"airport":"..","delay":15,"scheduled":"..","estimated":".."},
     *   "arrival":{"airport":"..","delay":0,"estimated":".."}}]}；
     * 失败形如 {"error":{"code":"...","message":"..."}} 或 data 为空。
     */
    fun summarize(body: String, flightNo: String): String? {
        val root = runCatching {
            Json.parseToJsonElement(body).let { it as? JsonObject }
        }.getOrNull() ?: return null
        val data = root["data"]?.let { it as? JsonArray } ?: return null
        val flight = data.firstOrNull()?.let { it as? JsonObject } ?: return null
        val status = flight["flight_status"]
            ?.let { it as? JsonPrimitive }?.content ?: return null
        fun delayOf(node: String): Int? =
            (flight[node] as? JsonObject)?.get("delay")
                ?.let { it as? JsonPrimitive }?.content?.toIntOrNull()
                ?.takeIf { it > 0 }
        // flight_status: scheduled / active / landed / cancelled / incident / diverted
        val phase = when (status) {
            "landed" -> "已落地"
            "cancelled" -> "已取消"
            "diverted" -> "已备降"
            "incident" -> "出现异常状况"
            "active" -> "飞行中"
            else -> "按计划"
        }
        val delay = delayOf("departure") ?: delayOf("arrival")
        val delayLine = delay?.let { "，延误 $it 分钟" } ?: ""
        return "航班 $flightNo $phase$delayLine"
    }

    /** 是否已完结（落地/取消/备降）→ 决定任务是否标 DONE */
    fun isFinal(body: String): Boolean {
        val root = runCatching {
            Json.parseToJsonElement(body).let { it as? JsonObject }
        }.getOrNull() ?: return false
        val status = (root["data"] as? JsonArray)?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.get("flight_status")
            ?.let { it as? JsonPrimitive }?.content
        return status in setOf("landed", "cancelled", "diverted", "incident")
    }
}

@Singleton
class FlightClient @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** AviationStack 航班查询（access_key 由设置页配置）。失败返回 null。 */
    suspend fun query(accessKey: String, flightNo: String): String? =
        withContext(Dispatchers.IO) {
            if (accessKey.isBlank() || !FlightTracking.isFlightNo(flightNo)) return@withContext null
            runCatching {
                val url = "https://api.aviationstack.com/v1/flights" +
                    "?access_key=" + okhttp3.HttpUrl.Companion.encode(accessKey, "UTF-8") +
                    "&flight_iata=" + okhttp3.HttpUrl.Companion.encode(flightNo, "UTF-8") +
                    "&limit=1"
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { it.body?.string() }
            }.getOrNull()
        }
}
