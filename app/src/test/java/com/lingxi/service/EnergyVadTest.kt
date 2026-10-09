package com.lingxi

import com.lingxi.data.EnergyVad
import com.lingxi.data.VadConfig
import com.lingxi.data.VadEvent
import com.lingxi.data.segmentValid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyVadTest {

    private fun vad(hangoverMs: Int = 700) = EnergyVad(VadConfig(thresholdRms = 500.0, hangoverSilenceMs = hangoverMs))

    @Test
    fun `安静环境不触发`() {
        val v = vad()
        repeat(20) { assertNull(v.feed(50.0, 100)) }
    }

    @Test
    fun `超过阈值立即报 SpeechStart`() {
        val v = vad()
        assertNull(v.feed(50.0, 100))
        assertEquals(VadEvent.SpeechStart, v.feed(1200.0, 100))
    }

    @Test
    fun `静音挂起后才报 SpeechEnd`() {
        val v = vad(hangoverMs = 500)
        v.feed(1200.0, 100) // start
        assertNull(v.feed(50.0, 100))
        assertNull(v.feed(50.0, 100))
        assertNull(v.feed(50.0, 100))
        assertNull(v.feed(50.0, 100)) // 恰好 400ms < 500ms
        assertEquals(VadEvent.SpeechEnd, v.feed(50.0, 100)) // 500ms 达标
    }

    @Test
    fun `挂起期间再发声则重新计时`() {
        val v = vad(hangoverMs = 500)
        v.feed(1200.0, 100)
        v.feed(50.0, 100) // 100
        v.feed(50.0, 100) // 200
        assertNull(v.feed(900.0, 100)) // 语音帧：清零静音计时，不报事件
        v.feed(50.0, 100)
        v.feed(50.0, 100)
        v.feed(50.0, 100)
        v.feed(50.0, 100) // 400ms
        assertEquals(VadEvent.SpeechEnd, v.feed(50.0, 100)) // 500ms
    }

    @Test
    fun `reset 回到静音态`() {
        val v = vad()
        v.feed(1200.0, 100) // in speech
        v.feed(50.0, 300)
        v.reset()
        // reset 后需要重新走 SpeechStart
        assertEquals(VadEvent.SpeechStart, v.feed(1200.0, 100))
    }

    @Test
    fun `SpeechEnd 后可再次开始新段`() {
        val v = vad(hangoverMs = 200)
        assertEquals(VadEvent.SpeechStart, v.feed(1200.0, 100))
        assertEquals(VadEvent.SpeechEnd, v.feed(50.0, 200))
        assertEquals(VadEvent.SpeechStart, v.feed(900.0, 100))
    }

    @Test
    fun `语音段时长校验 过短丢弃`() {
        assertTrue(segmentValid(300))
        assertTrue(segmentValid(2500))
        assertFalse(segmentValid(299))
        assertFalse(segmentValid(0))
    }
}
