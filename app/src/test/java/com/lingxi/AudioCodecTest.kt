package com.lingxi

import com.lingxi.data.PcmWav
import com.lingxi.data.rmsOfPcm
import com.lingxi.data.transcriptionsUrl
import com.lingxi.data.trimSilence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCodecTest {

    private fun silence(ms: Int, sampleRate: Int = 16_000): ByteArray =
        ByteArray(sampleRate * ms / 1000 * 2)

    private fun sine(ms: Int, sampleRate: Int = 16_000, amp: Int = 4000): ByteArray {
        val n = sampleRate * ms / 1000
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (amp * kotlin.math.sin(2 * Math.PI * 440.0 * i / sampleRate)).toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) { p.copyInto(out, pos); pos += p.size }
        return out
    }

    @Test
    fun `静音 RMS 为零`() {
        assertEquals(0.0, rmsOfPcm(silence(100)), 0.0001)
    }

    @Test
    fun `正弦 RMS 约等于 峰值除根号二`() {
        val rms = rmsOfPcm(sine(100, amp = 8000))
        assertEquals(8000 * 0.7071, rms, 300.0)
    }

    @Test
    fun `trimSilence 去头尾静音并保留余量`() {
        val sampleRate = 16_000
        val pcm = concat(silence(300, sampleRate), sine(500, sampleRate), silence(300, sampleRate))
        val trimmed = trimSilence(pcm, sampleRate, threshold = 500.0, keepMs = 120)
        val expected = sampleRate * (500 + 2 * 120) / 1000 * 2
        // 余量不会越界到相邻静音区外（头尾各 300ms 静音 > keepMs），允许 1 帧误差
        assertTrue("actual=${trimmed.size} expected~$expected", Math.abs(trimmed.size - expected) <= sampleRate / 25 * 2)
    }

    @Test
    fun `trimSilence 整段静音返回空`() {
        assertTrue(trimSilence(silence(500), 16_000).isEmpty())
    }

    @Test
    fun `PcmWav 头部与长度字段正确`() {
        val pcm = silence(100) // 1600 samples = 3200 bytes
        val wav = PcmWav.encode(pcm, sampleRate = 16_000)
        assertEquals(44 + pcm.size, wav.size)
        assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12)))
        assertEquals("data", String(wav.copyOfRange(36, 40)))
        val riffSize = (wav[4].toInt() and 0xFF) or ((wav[5].toInt() and 0xFF) shl 8) or
            ((wav[6].toInt() and 0xFF) shl 16) or ((wav[7].toInt() and 0xFF) shl 24)
        assertEquals(36 + pcm.size, riffSize)
        val sampleRate = (wav[24].toInt() and 0xFF) or ((wav[25].toInt() and 0xFF) shl 8)
        assertEquals(16_000, sampleRate)
    }

    @Test
    fun `transcriptionsUrl 规整各种 baseUrl`() {
        assertEquals("https://a.com/v1/audio/transcriptions", transcriptionsUrl("https://a.com/v1"))
        assertEquals("https://a.com/v1/audio/transcriptions", transcriptionsUrl("https://a.com/v1/"))
        assertEquals("https://a.com/v1/audio/transcriptions", transcriptionsUrl("https://a.com/v1/audio/transcriptions"))
    }
}
