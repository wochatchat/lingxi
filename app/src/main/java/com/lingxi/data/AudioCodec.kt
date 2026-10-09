package com.lingxi.data

/**
 * PCM16 → WAV 封装（纯函数，供云 ASR multipart 上传用）。
 */
object PcmWav {
    fun encode(pcm: ByteArray, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataLen = pcm.size
        val out = ByteArray(44 + dataLen)
        fun le32(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte(); out[off + 1] = ((v shr 8) and 0xFF).toByte()
            out[off + 2] = ((v shr 16) and 0xFF).toByte(); out[off + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun le16(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte(); out[off + 1] = ((v shr 8) and 0xFF).toByte()
        }
        "RIFF".toByteArray().copyInto(out, 0)
        le32(4, 36 + dataLen)
        "WAVE".toByteArray().copyInto(out, 8)
        "fmt ".toByteArray().copyInto(out, 12)
        le32(16, 16)              // fmt chunk size
        le16(20, 1)               // PCM
        le16(22, channels)
        le32(24, sampleRate)
        le32(28, byteRate)
        le16(32, blockAlign)
        le16(34, bitsPerSample)
        "data".toByteArray().copyInto(out, 36)
        le32(40, dataLen)
        pcm.copyInto(out, 44)
        return out
    }
}

/** 一段 PCM16 字节的 RMS 能量（little-endian 采样） */
fun rmsOfPcm(pcm: ByteArray): Double {
    if (pcm.size < 2) return 0.0
    var sum = 0.0
    var n = 0
    var i = 0
    while (i + 1 < pcm.size) {
        val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
        sum += sample.toDouble() * sample
        n++
        i += 2
    }
    if (n == 0) return 0.0
    return kotlin.math.sqrt(sum / n)
}

/**
 * 去头尾静音（能量阈值，头尾各保留 [keepMs] 防切字头字尾）。纯函数可测。
 * 整段皆静音时返回空数组（上层据此判定「没有有效语音」）。
 */
fun trimSilence(pcm: ByteArray, sampleRate: Int, threshold: Double = 500.0, keepMs: Int = 120): ByteArray {
    val frameBytes = sampleRate / 25 * 2 // 40ms 一帧（16bit 单声道）
    val keepBytes = sampleRate / 1000 * keepMs * 2
    val starts = mutableListOf<Int>()
    val ends = mutableListOf<Int>()
    var i = 0
    while (i < pcm.size) {
        val end = minOf(pcm.size, i + frameBytes)
        if (rmsOfPcm(pcm.copyOfRange(i, end)) > threshold) { starts.add(i); ends.add(end) }
        i = end
    }
    if (starts.isEmpty()) return ByteArray(0)
    val from = maxOf(0, starts.first() - keepBytes)
    val to = minOf(pcm.size, ends.last() + keepBytes)
    return pcm.copyOfRange(from, to)
}
