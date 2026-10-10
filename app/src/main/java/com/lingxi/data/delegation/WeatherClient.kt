package com.lingxi.data.delegation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 天气拉取（F11 晨报用）：Open-Meteo 免费 API，无需 Key。
 * geocoding 城市名 → 经纬度 → current_weather。
 * 失败一律返回 null（晨报降级为无天气，不阻塞整体）。
 */
@Singleton
class WeatherClient @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** 「上海」→「多云 12°C」（失败返回 null） */
    suspend fun currentSummary(city: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val (lat, lon) = geocode(city) ?: return@runCatching null
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current_weather=true&timezone=auto"
            val body = client.newCall(Request.Builder().url(url).build()).execute()
                .use { it.body?.string() } ?: return@runCatching null
            val root = json.parseToJsonElement(body).jsonObject
            val current = root["current_weather"]?.jsonObject ?: return@runCatching null
            val temp = current["temperature"]?.jsonPrimitive?.content ?: return@runCatching null
            val code = current["weathercode"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            "${codeName(code)} ${temp}°C"
        }.getOrNull()
    }

    /** 城市名 → (lat, lon)，失败返回 null */
    private fun geocode(city: String): Pair<Double, Double>? {
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            java.net.URLEncoder.encode(city, "UTF-8") + "&count=1&language=zh"
        val body = client.newCall(Request.Builder().url(url).build()).execute()
            .use { it.body?.string() } ?: return null
        val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray ?: return null
        if (results.isEmpty()) return null
        val obj = results[0].jsonObject
        val lat = obj["latitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
        val lon = obj["longitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
        return lat to lon
    }

    /** Open-Meteo weathercode → 中文短语（常用档位） */
    fun codeName(code: Int): String = when (code) {
        0 -> "晴"
        1, 2 -> "少云"
        3 -> "阴"
        45, 48 -> "雾"
        in 51..57 -> "毛毛雨"
        in 61..67 -> "雨"
        in 71..77 -> "雪"
        in 80..82 -> "阵雨"
        in 85..86 -> "阵雪"
        in 95..99 -> "雷雨"
        else -> "天气多变"
    }
}
