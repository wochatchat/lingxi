package com.lingxi

import com.lingxi.data.keepalive.KeepAlivePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R11 保活与自启纯逻辑 */
class KeepAlivePolicyTest {

    // ---------- shouldStartOnBoot ----------

    @Test
    fun `自启开且有常驻开关时开机启动`() {
        assertTrue(KeepAlivePolicy.shouldStartOnBoot(autoStart = true, capsule = true, listen = false))
        assertTrue(KeepAlivePolicy.shouldStartOnBoot(autoStart = true, capsule = false, listen = true))
        assertTrue(KeepAlivePolicy.shouldStartOnBoot(autoStart = true, capsule = true, listen = true))
    }

    @Test
    fun `自启关时不启动`() {
        assertFalse(KeepAlivePolicy.shouldStartOnBoot(autoStart = false, capsule = true, listen = true))
    }

    @Test
    fun `自启开但常驻全关时不启动`() {
        assertFalse(KeepAlivePolicy.shouldStartOnBoot(autoStart = true, capsule = false, listen = false))
    }

    // ---------- shouldRevive ----------

    @Test
    fun `要常驻但服务死了才拉起`() {
        assertTrue(KeepAlivePolicy.shouldRevive(serviceAlive = false, wantService = true))
    }

    @Test
    fun `服务活着不重复拉起`() {
        assertFalse(KeepAlivePolicy.shouldRevive(serviceAlive = true, wantService = true))
    }

    @Test
    fun `不要常驻时永不拉起`() {
        assertFalse(KeepAlivePolicy.shouldRevive(serviceAlive = false, wantService = false))
        assertFalse(KeepAlivePolicy.shouldRevive(serviceAlive = true, wantService = false))
    }

    @Test
    fun `判定为纯布尔函数`() {
        assertEquals(false, KeepAlivePolicy.shouldStartOnBoot(false, false, false))
        assertEquals(false, KeepAlivePolicy.shouldRevive(false, false))
    }
}
