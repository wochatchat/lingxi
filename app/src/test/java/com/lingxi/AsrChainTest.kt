package com.lingxi

import com.lingxi.data.asr.AsrPath
import com.lingxi.data.asr.fallbackAfterCloudFailure
import com.lingxi.data.asr.planAsrPath
import com.lingxi.data.net.NetTier
import org.junit.Assert.assertEquals
import org.junit.Test

class AsrChainTest {

    @Test
    fun `在线有云 Provider → 云端优先`() {
        assertEquals(AsrPath.CLOUD, planAsrPath(true, true, NetTier.ONLINE, false))
    }

    @Test
    fun `全离线开关 → 系统识别（可用时）`() {
        assertEquals(AsrPath.SYSTEM, planAsrPath(true, true, NetTier.ONLINE, true))
        assertEquals(AsrPath.NONE, planAsrPath(true, false, NetTier.ONLINE, true))
    }

    @Test
    fun `断网 → 系统识别兜底或无通道`() {
        assertEquals(AsrPath.SYSTEM, planAsrPath(true, true, NetTier.OFFLINE, false))
        assertEquals(AsrPath.NONE, planAsrPath(true, false, NetTier.OFFLINE, false))
    }

    @Test
    fun `弱网有云 → 仍走云端（失败再回落）`() {
        assertEquals(AsrPath.CLOUD, planAsrPath(true, true, NetTier.WEAK, false))
    }

    @Test
    fun `未配置 Provider → 系统识别兜底`() {
        assertEquals(AsrPath.SYSTEM, planAsrPath(false, true, NetTier.ONLINE, false))
        assertEquals(AsrPath.NONE, planAsrPath(false, false, NetTier.ONLINE, false))
    }

    @Test
    fun `云端失败回落 - 弱网且系统可用才回落`() {
        assertEquals(AsrPath.SYSTEM, fallbackAfterCloudFailure(true, NetTier.WEAK))
        assertEquals(AsrPath.NONE, fallbackAfterCloudFailure(false, NetTier.WEAK))
        assertEquals(AsrPath.NONE, fallbackAfterCloudFailure(true, NetTier.OFFLINE))
    }
}
