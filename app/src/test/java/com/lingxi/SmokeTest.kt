package com.lingxi

import org.junit.Assert.assertEquals
import org.junit.Test

class SmokeTest {
    @Test
    fun versionBumpMath() {
        // 0.1.0 起步；build.sh 负责 bump，这里验证工具链（CI 单测通路）
        val patch = 99 + 1
        val carried = if (patch >= 100) 1 else 0
        org.junit.Assert.assertEquals(100, patch)
        org.junit.Assert.assertEquals(1, carried)
    }
}
