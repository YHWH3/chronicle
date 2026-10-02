package com.chronicle.app.media

/**
 * Pure PCM/WAV helpers — extracted from [WavRecorder] so the byte-level
 * logic (header layout, peak detection) is unit-testable without Android.
 */
internal object WavPcm {

    /**
     * The canonical 44-byte RIFF/WAVE header for 16-bit mono PCM.
     * [dataBytes] is the number of PCM payload bytes actually on disk.
     */
    fun header(sampleRate: Int, dataBytes: Long): ByteArray {
        require(dataBytes >= 0) { "dataBytes < 0" }
        val data = dataBytes.coerceAtMost((Int.MAX_VALUE - 36).toLong()).toInt()
        val b = ByteArray(44)
        fun ascii(offset: Int, s: String) {
            for (i in s.indices) b[offset + i] = s[i].code.toByte()
        }
        fun int32(offset: Int, v: Int) {
            b[offset] = (v and 0xFF).toByte()
            b[offset + 1] = ((v shr 8) and 0xFF).toByte()
            b[offset + 2] = ((v shr 16) and 0xFF).toByte()
            b[offset + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun int16(offset: Int, v: Int) {
            b[offset] = (v and 0xFF).toByte()
            b[offset + 1] = ((v shr 8) and 0xFF).toByte()
        }
        ascii(0, "RIFF")
        int32(4, 36 + data)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        int32(16, 16) // PCM fmt chunk size
        int16(20, 1) // PCM format tag
        int16(22, 1) // mono
        int32(24, sampleRate)
        int32(28, sampleRate * 2) // byte rate: 16-bit mono
        int16(32, 2) // block align
        int16(34, 16) // bits per sample
        ascii(36, "data")
        int32(40, data)
        return b
    }

    /** Peak absolute amplitude of the first [n] bytes of little-endian PCM16. */
    fun pcmPeak(buf: ByteArray, n: Int): Int {
        var max = 0
        var i = 0
        while (i + 1 < n) {
            val s = (buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)
            if (kotlin.math.abs(s) > max) max = kotlin.math.abs(s)
            i += 2
        }
        return max
    }
}
