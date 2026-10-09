package com.lingxi.data

import android.media.AudioFormat
import android.media.AudioRecord

/**
 * 按住说话式采集（push-to-talk，R2 形态；常听 G 级门控 R3+）。
 * start 后在后台线程 [readMore] 持续读 PCM，stop 返回整段 PCM16（已去头尾静音）。
 */
class MicRecorder(private val sampleRate: Int = SAMPLE_RATE) {

    private var record: AudioRecord? = null
    private val buf = java.io.ByteArrayOutputStream()

    val sampleRateHz: Int get() = sampleRate

    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, sampleRate), // ≥1s 缓冲
            )
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        record = rec
        rec.startRecording()
        return true
    }

    /** 在采集线程循环调用；返回 false 表示未在录音 */
    fun readMore(): Boolean {
        val rec = record ?: return false
        val chunk = ByteArray(CHUNK_BYTES)
        val n = rec.read(chunk, 0, CHUNK_BYTES)
        if (n > 0) synchronized(buf) { buf.write(chunk, 0, n) }
        return n > 0
    }

    /** 停止并返回整段 PCM16（小端）；已去头尾静音 */
    fun stop(): Pair<ByteArray, Int> {
        val rec = record
        record = null
        rec?.runCatching { stop() }
        rec?.release()
        val pcm = synchronized(buf) { buf.toByteArray() }
        buf.reset()
        val trimmed = trimSilence(pcm, sampleRate)
        return trimmed to sampleRate
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHUNK_BYTES = 3200 // 100ms @16k 16bit mono
    }
}
