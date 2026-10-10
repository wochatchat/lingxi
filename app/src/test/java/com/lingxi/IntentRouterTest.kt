package com.lingxi

import com.lingxi.data.IntentRouter
import com.lingxi.data.IntentRouter.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IntentRouterTest {

    @Test
    fun `设闹钟走 DIRECT 且解析时间`() {
        val route = IntentRouter.route("设一个早上7点的闹钟") as Route.Direct
        assertEquals("set_alarm", route.toolName)
        assertEquals("7", route.args["hour"])
        assertEquals("0", route.args["minute"])
    }

    @Test
    fun `提醒我走 DIRECT`() {
        val route = IntentRouter.route("提醒我下午3点开会")
        assertTrue(route is Route.Direct)
        route as Route.Direct
        assertEquals("set_alarm", route.toolName)
        assertEquals("15", route.args["hour"])
        assertEquals("开会", route.args["label"])
    }

    @Test
    fun `疑问句不算设闹钟`() {
        // "我的闹钟怎么不响" 是提问 → 不 DIRECT
        assertTrue(IntentRouter.route("我的闹钟怎么不响") !is Route.Direct)
    }

    @Test
    fun `每天提醒走委托任务 ToolsLlm 而非 set_alarm`() {
        val route = IntentRouter.route("每天下午3点提醒我打坐")
        assertTrue(route is Route.ToolsLlm)
    }

    @Test
    fun `盯快递走 ToolsLlm`() {
        val route = IntentRouter.route("帮我盯着这个快递到了告诉我")
        assertTrue(route is Route.ToolsLlm)
    }

    @Test
    fun `打开应用走 DIRECT`() {
        val route = IntentRouter.route("打开微信") as Route.Direct
        assertEquals("open_app", route.toolName)
        assertEquals("微信", route.args["app_name"])
    }

    @Test
    fun `网页搜索走 DIRECT`() {
        val route = IntentRouter.route("搜索量子计算") as Route.Direct
        assertEquals("web_search", route.toolName)
        assertEquals("量子计算", route.args["query"])
    }

    @Test
    fun `动作意图走 tools 闲聊走 chat`() {
        assertEquals(Route.ToolsLlm, IntentRouter.route("帮我把闹钟设到七点半"))
        assertEquals(Route.ToolsLlm, IntentRouter.route("用闹钟功能设个七点半"))
        assertEquals(Route.Chat, IntentRouter.route("你好呀"))
        assertEquals(Route.Chat, IntentRouter.route("帮我写一首诗"))
        assertEquals(Route.Chat, IntentRouter.route("今天心情不太好"))
    }

    @Test
    fun `时间提取 - 冒号与点分格式`() {
        assertEquals(7 to 30, IntentRouter.extractAlarmTime("7:30 闹钟"))
        assertEquals(23 to 30, IntentRouter.extractAlarmTime("23点30分"))
        assertEquals(7 to 0, IntentRouter.extractAlarmTime("7点"))
    }

    @Test
    fun `下午晚上加12`() {
        assertEquals(15 to 0, IntentRouter.extractAlarmTime("下午3点提醒我"))
        assertEquals(21 to 30, IntentRouter.extractAlarmTime("晚上9点半"))
    }

    @Test
    fun `早上上午不加12`() {
        assertEquals(7 to 0, IntentRouter.extractAlarmTime("早上7点"))
    }

    // ---- R8 F10 截屏意图 ----

    @Test
    fun `截屏意图路由到 Screenshot`() {
        assertEquals(IntentRouter.Route.Screenshot, IntentRouter.route("看看屏幕上这个"))
        assertEquals(IntentRouter.Route.Screenshot, IntentRouter.route("帮我截屏看看"))
        assertEquals(IntentRouter.Route.Screenshot, IntentRouter.route("屏幕上写了什么？"))
        assertEquals(IntentRouter.Route.Screenshot, IntentRouter.route("看一下屏幕"))
        assertEquals(IntentRouter.Route.Screenshot, IntentRouter.route("读一下屏幕上的字"))
    }

    @Test
    fun `非截屏话术不走截图路由`() {
        assertNotEquals(IntentRouter.Route.Screenshot, IntentRouter.route("今天心情不错"))
        assertNotEquals(IntentRouter.Route.Screenshot, IntentRouter.route("帮我查快递"))
    }
}
