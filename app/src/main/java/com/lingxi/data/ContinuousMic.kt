package com.lingxi.data

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * 连续采集（R3 常听管线用）：start 后在采集线程循环 [read] 拉取固定 100ms PCM 块，
 * 不在内部累积（缓冲逻辑交给上层 VAD/分段器）。与 MicRecorder（按住说话，累积式）互补。
 */
class ContinuousMic(private val sampleRate: Int = SAMPLE_RATE) {

    private var record: AudioRecord? = null
    private val chunk = ByteArray(CHUNK_BYTES)

    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, sampleRate),
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

    /** 阻塞读一个 100ms 块，返回有效字节数（≤0 表示未在录音） */
    fun read(): ByteArray? {
        val rec = record ?: return null
        val n = rec.read(chunk, 0, CHUNK_BYTES)
        return if (n > 0) chunk.copyOf(n) else null
    }

    fun stop() {
        val rec = record
        record = null
        rec?.runCatching { stop() }
        rec?.release()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_BYTES = 3200 // 100ms @16k 16bit mono
        const val CHUNK_MS = 100
    }
}
