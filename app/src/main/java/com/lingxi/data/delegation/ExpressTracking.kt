package com.lingxi.data.delegation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * F13 快递跟踪（R9）：快递100 官方查询接口（poll.kuaidi100.com）。
 * 需要「快递查询 customer + key」（快递100 企业版参数），走设置页配置；
 * 未配置时 check 返回 null（巡查打卡回落，不炸任务）。
 */
object ExpressTracking {

    /** 从任务标题/口述文本里提取快递单号：8-15 位纯数字，或 1-3 位字母前缀+数字（共 8-15 位）。 */
    fun extractTrackingNo(text: String): String? {
        val m = Regex("""(?<![A-Za-z0-9])([A-Za-z]{1,3}[0-9]{7,14}|[0-9]{8,15})(?![0-9A-Za-z])""")
            .find(text.replace("　", " "))
            ?: return null
        return m.groupValues[1].uppercase()
    }

    /** 猜快递公司编码：顺丰=shunfeng 等常用前缀映射；猜不出回空串（快递100 自动识别）。 */
    fun guessCompany(text: String): String {
        val t = text.lowercase()
        return when {
            "顺丰" in t || "sf" == t.trim() -> "shunfeng"
            "中通" in t || "zto" in t -> "zhongtong"
            "圆通" in t || "yto" in t -> "yuantong"
            "韵达" in t || "yunda" in t -> "yunda"
            "申通" in t || "sto" in t -> "shengtong"
            "邮政" in t || "ems" in t -> "ems"
            "京东" in t || "jd" in t -> "jd"
            "极兔" in t || "jt" in t -> "jtexpress"
            else -> ""
        }
    }

    /**
     * 解析快递100 query 响应 → 一句话状态摘要（纯函数，可单测）。
     * 响应形如 {"message":"ok","status":"200","state":"3","ischeck":"1",
     *          "data":[{"time":"..","context":"已签收"},...]}；
     * 失败形如 {"status":"201","message":"查询无结果"}。
     */
    fun summarize(body: String, trackingNo: String): String? {
        val root = runCatching {
            Json.parseToJsonElement(body).let { it as? kotlinx.serialization.json.JsonObject }
        }.getOrNull() ?: return null
        val status = root["status"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
        if (status != "200") return null
        val ischeck = root["ischeck"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
        val state = root["state"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
        val last = root["data"]?.let { it as? kotlinx.serialization.json.JsonArray }
            ?.lastOrNull()?.let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("context")?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
            ?: return null
        // state: 0在途 1揽收 2疑难 3签收 4退签 5派件 6退回；ischeck=1 表示已完结
        val phase = when {
            ischeck == "1" && state in setOf("3") -> "已签收"
            ischeck == "1" && state in setOf("4", "6") -> "已退回/拒收"
            state == "5" -> "派送中"
            else -> "在途"
        }
        return "快递 $trackingNo $phase：$last"
    }

    /** 是否已完结（ischeck=1）→ 决定任务是否标 DONE */
    fun isFinal(body: String): Boolean {
        val root = runCatching {
            Json.parseToJsonElement(body).let { it as? kotlinx.serialization.json.JsonObject }
        }.getOrNull() ?: return false
        return root["ischeck"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content == "1"
    }

    /**
     * 完结后是否需要追问取件码（R10）：已签收（state=3）且最新轨迹落在驿站/快递柜/
     * 代收点，说明件已放到代收点待自取 → 追问取件码；快递员直接送到手的不用问。
     */
    fun needsPickupFollowup(body: String): Boolean {
        val root = runCatching {
            Json.parseToJsonElement(body).let { it as? kotlinx.serialization.json.JsonObject }
        }.getOrNull() ?: return false
        val state = root["state"]?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
        val last = (root["data"] as? kotlinx.serialization.json.JsonArray)
            ?.lastOrNull()?.let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("context")?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
            .orEmpty()
        return state == "3" && STATION_WORDS.any { it in last }
    }

    private val STATION_WORDS = listOf("驿站", "快递柜", "代收点", "货架", "丰巢", "菜鸟", "自提柜", "代收")
}

@Singleton
class Kuaidi100Client @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** 快递100 查询（customer/key 由设置页配置）。失败返回 null。 */
    suspend fun query(
        customer: String,
        key: String,
        company: String,
        trackingNo: String,
    ): String? = withContext(Dispatchers.IO) {
        if (customer.isBlank() || key.isBlank() || trackingNo.isBlank()) return@withContext null
        runCatching {
            val param = """{"com":"$company","num":"$trackingNo","phone":""}"""
            val sign = md5(param + key + customer).uppercase()
            val form = FormBody.Builder()
                .add("customer", customer)
                .add("param", param)
                .add("sign", sign)
                .build()
            val request = Request.Builder()
                .url("https://poll.kuaidi100.com/poll/query.do")
                .post(form)
                .build()
            client.newCall(request).execute().use { it.body?.string() }
        }.getOrNull()
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
