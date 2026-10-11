package com.lingxi

import com.lingxi.data.net.NetTier
import com.lingxi.data.net.resolveNetTier
import org.junit.Assert.assertEquals
import org.junit.Test

class NetTierTest {

    @Test
    fun `无网 → OFFLINE`() {
        assertEquals(NetTier.OFFLINE, resolveNetTier(hasNetwork = false, isValidated = false))
        assertEquals(NetTier.OFFLINE, resolveNetTier(hasNetwork = false, isValidated = true))
    }

    @Test
    fun `有网未过验证 captive portal → WEAK`() {
        assertEquals(NetTier.WEAK, resolveNetTier(hasNetwork = true, isValidated = false))
    }

    @Test
    fun `有网已验证 → ONLINE`() {
        assertEquals(NetTier.ONLINE, resolveNetTier(hasNetwork = true, isValidated = true))
    }
}
