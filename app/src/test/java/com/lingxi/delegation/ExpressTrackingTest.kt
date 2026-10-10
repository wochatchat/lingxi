package com.lingxi.delegation

import com.lingxi.data.delegation.ExpressTracking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R9 F13：快递单号提取 / 公司猜测 / 快递100 响应解析（纯函数） */
class ExpressTrackingTest {

    // ---- extractTrackingNo ----

    @Test
    fun extract_pureDigits() {
        assertEquals("1234567890", ExpressTracking.extractTrackingNo("盯着这个快递 1234567890 到了告诉我"))
    }

    @Test
    fun extract_letterPrefixed() {
        assertEquals("SF1234567890", ExpressTracking.extractTrackingNo("顺丰 SF1234567890 帮我盯着"))
    }

    @Test
    fun extract_none() {
        assertNull(ExpressTracking.extractTrackingNo("每天下午 3 点提醒我打坐"))
    }

    @Test
    fun extract_tooShort() {
        assertNull(ExpressTracking.extractTrackingNo("记住 12345 这个数"))
    }

    @Test
    fun extract_uppercase() {
        assertEquals("SF12345678", ExpressTracking.extractTrackingNo("sf12345678"))
    }

    @Test
    fun guessCompany_common() {
        assertEquals("shunfeng", ExpressTracking.guessCompany("顺丰快递到了吗"))
        assertEquals("zhongtong", ExpressTracking.guessCompany("中通 ZTO 盯一下"))
        assertEquals("", ExpressTracking.guessCompany("我的快递"))
    }

    @Test
    fun summarize_inTransit() {
        val body = """
            {"message":"ok","status":"200","state":"0","ischeck":"0",
             "data":[{"time":"2026-10-09 10:00:00","context":"包裹已到达上海转运中心"},
                     {"time":"2026-10-10 08:00:00","context":"运输中"}]}
        """.trimIndent()
        assertEquals(
            "快递 1234567890 在途：运输中",
            ExpressTracking.summarize(body, "1234567890"),
        )
    }

    @Test
    fun summarize_signed() {
        val body = """
            {"message":"ok","status":"200","state":"3","ischeck":"1",
             "data":[{"time":"2026-10-09 10:00:00","context":"已发货"},
                     {"time":"2026-10-10 09:30:00","context":"您的快件已签收，如有疑问请致电快递员"}]}
        """.trimIndent()
        assertEquals(
            "快递 SF12345678 已签收：您的快件已签收，如有疑问请致电快递员",
            ExpressTracking.summarize(body, "SF12345678"),
        )
    }

    @Test
    fun summarize_error() {
        assertNull(ExpressTracking.summarize("""{"status":"201","message":"查询无结果"}""", "1234567890"))
        assertNull(ExpressTracking.summarize("not json", "1234567890"))
    }

    @Test
    fun isFinal() {
        assertTrue(ExpressTracking.isFinal("""{"status":"200","ischeck":"1","state":"3"}"""))
        assertFalse(ExpressTracking.isFinal("""{"status":"200","ischeck":"0","data":[]}"""))
        assertFalse(ExpressTracking.isFinal("not json"))
    }

    // ---- R10：完结追问取件码 ----

    @Test
    fun pickupFollowup_stationSignoff() {
        // 签收 + 最新轨迹在驿站 → 追问取件码
        val body = """
            {"status":"200","state":"3","ischeck":"1",
             "data":[{"time":"2026-10-10 08:00:00","context":"运输中"},
                     {"time":"2026-10-10 09:00:00","context":"您的快件已放入菜鸟驿站，请凭取件码领取"}]}
        """.trimIndent()
        assertTrue(ExpressTracking.needsPickupFollowup(body))
    }

    @Test
    fun pickupFollowup_directSignoff() {
        // 签收但无驿站/快递柜字样（快递员送到手）→ 不追问
        val body = """
            {"status":"200","state":"3","ischeck":"1",
             "data":[{"context":"您的快件已签收，如有疑问请致电快递员"}]}
        """.trimIndent()
        assertFalse(ExpressTracking.needsPickupFollowup(body))
    }

    @Test
    fun pickupFollowup_notSigned() {
        // 在途/派送中不算完结 → 不追问
        val body = """
            {"status":"200","state":"5","ischeck":"0",
             "data":[{"context":"快件已到达菜鸟驿站"}]}
        """.trimIndent()
        assertFalse(ExpressTracking.needsPickupFollowup(body))
    }
}
