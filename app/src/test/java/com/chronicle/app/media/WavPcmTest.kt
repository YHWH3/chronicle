package com.chronicle.app.media

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The pure byte-level parts of the WAV take — header layout and peak scan.
 * Bigger-picture stop-ordering is covered by the recorder's suspend-stop
 * design (join before release) which these helpers feed.
 */
class WavPcmTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun le32(b: ByteArray, off: Int) =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun le16(b: ByteArray, off: Int) =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `header is a canonical 44-byte RIFF WAVE for mono pcm16`() {
        val h = WavPcm.header(16_000, 3_200)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals("WAVE", String(h, 8, 4))
        assertEquals("fmt ", String(h, 12, 4))
        assertEquals(36L + 3_200, le32(h, 4)) // RIFF chunk size
        assertEquals(16, le32(h, 16).toInt()) // fmt chunk size
        assertEquals(1, le16(h, 20)) // PCM
        assertEquals(1, le16(h, 22)) // mono
        assertEquals(16_000L, le32(h, 24)) // sample rate
        assertEquals(32_000L, le32(h, 28)) // byte rate
        assertEquals(2, le16(h, 32)) // block align
        assertEquals(16, le16(h, 34)) // bits per sample
        assertEquals("data", String(h, 36, 4))
        assertEquals(3_200L, le32(h, 40)) // data chunk size
    }

    @Test
    fun `header written over placeholder yields a playable file shape`() {
        val file = File(tmp.newFolder(), "take.wav")
        // 44 placeholder bytes + 4 bytes of PCM — what a finished take looks like.
        file.writeBytes(ByteArray(44) + byteArrayOf(1, 0, 255.toByte(), 127))
        val dataBytes = file.length() - 44
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(WavPcm.header(WavRecorder.SAMPLE_RATE, dataBytes))
        }
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(12)
            raf.readFully(head)
            assertEquals("RIFF", String(head, 0, 4))
            assertEquals(raf.length() - 8, le32(head, 4)) // RIFF size = file - 8
        }
    }

    @Test
    fun `pcmPeak finds the loudest little-endian sample`() {
        val buf = byteArrayOf(
            0, 0,          // 0
            0, 16,         // 4096
            0xFF.toByte(), 0x7F, // 32767 — the max positive int16
            0, 32,         // 8192
        )
        assertEquals(32767, WavPcm.pcmPeak(buf, buf.size))
        assertEquals(0, WavPcm.pcmPeak(buf, 2))
        assertTrue(WavPcm.pcmPeak(ByteArray(0), 0) == 0)
    }
}
