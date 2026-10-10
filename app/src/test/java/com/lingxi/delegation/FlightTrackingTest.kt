package com.lingxi.delegation

import com.lingxi.data.delegation.FlightTracking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R10 F13：航班号提取 / AviationStack 响应解析（纯函数） */
class FlightTrackingTest {

    // ---- extractFlightNo ----

    @Test
    fun extract_basic() {
        assertEquals("CA1234", FlightTracking.extractFlightNo("帮我盯着 CA1234 落地没"))
    }

    @Test
    fun extract_lowercaseToUpper() {
        assertEquals("MU5678", FlightTracking.extractFlightNo("盯一下 mu5678"))
    }

    @Test
    fun extract_threeDigit() {
        assertEquals("ZH987", FlightTracking.extractFlightNo("航班 ZH987 到哪了"))
    }

    @Test
    fun extract_rejectsExpressNo() {
        // 快递单号（2字母+8位数字）不应误判为航班号
        assertNull(FlightTracking.extractFlightNo("盯 SF1234567890 到了告诉我"))
    }

    @Test
    fun extract_rejectsPureDigits() {
        assertNull(FlightTracking.extractFlightNo("记住 12345678"))
    }

    // ---- summarize ----

    private fun body(status: String, delay: Int? = null) = """
        {"data":[{"flight":{"iata":"CA1234"},"flight_status":"$status",
        "departure":{"airport":"Beijing Capital","delay":${delay ?: 0},"scheduled":"2026-10-10T08:00:00","estimated":"2026-10-10T08:00:00"},
        "arrival":{"airport":"Shanghai Pudong","delay":0,"estimated":"2026-10-10T10:30:00"}}]}
    """.trimIndent()

    @Test
    fun summarize_activeWithDelay() {
        assertEquals(
            "航班 CA1234 飞行中，延误 15 分钟",
            FlightTracking.summarize(body("active", 15), "CA1234"),
        )
    }

    @Test
    fun summarize_landed() {
        assertEquals("航班 CA1234 已落地", FlightTracking.summarize(body("landed"), "CA1234"))
    }

    @Test
    fun summarize_cancelled() {
        assertEquals("航班 CA1234 已取消", FlightTracking.summarize(body("cancelled"), "CA1234"))
    }

    @Test
    fun summarize_scheduled() {
        assertEquals("航班 CA1234 按计划", FlightTracking.summarize(body("scheduled"), "CA1234"))
    }

    @Test
    fun summarize_errorOrEmpty() {
        assertNull(FlightTracking.summarize("""{"error":{"code":"x","message":"bad"}}""", "CA1234"))
        assertNull(FlightTracking.summarize("""{"data":[]}""", "CA1234"))
        assertNull(FlightTracking.summarize("not json", "CA1234"))
    }

    // ---- isFinal ----

    @Test
    fun isFinal_statuses() {
        assertTrue(FlightTracking.isFinal(body("landed")))
        assertTrue(FlightTracking.isFinal(body("cancelled")))
        assertTrue(FlightTracking.isFinal(body("diverted")))
        assertFalse(FlightTracking.isFinal(body("active")))
        assertFalse(FlightTracking.isFinal(body("scheduled")))
        assertFalse(FlightTracking.isFinal("not json"))
    }
}
