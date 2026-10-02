package com.chronicle.app.media

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * BE-16 — the WAV streamer walks RIFF chunks correctly, decodes
 * little-endian PCM16, feeds the take in bounded passes, and refuses
 * absurd sizes up front instead of allocating them.
 */
class WavPcmStreamTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun writeWav(
        pcm: ByteArray,
        extraChunks: List<Pair<String, ByteArray>> = emptyList(),
    ): File {
        val file = File(tmp.newFolder(), "take.wav")
        file.writeBytes(WavPcm.header(16_000, pcm.size.toLong()) + pcm)
        if (extraChunks.isEmpty()) return file
        // Rebuild with non-data chunks between header and data: splice the
        // 44-byte canonical header into RIFF + fmt + extras + data.
        val bos = java.io.ByteArrayOutputStream()
        bos.write(WavPcm.header(16_000, 0).copyOfRange(0, 36)) // RIFF..fmt end
        for ((id, bytes) in extraChunks) {
            bos.write(id.toByteArray())
            bos.write(
                byteArrayOf(
                    (bytes.size and 0xFF).toByte(),
                    ((bytes.size shr 8) and 0xFF).toByte(),
                    ((bytes.size shr 16) and 0xFF).toByte(),
                    ((bytes.size shr 24) and 0xFF).toByte(),
                ),
            )
            bos.write(bytes)
            if (bytes.size % 2 == 1) bos.write(0) // word-align pad
        }
        bos.write("data".toByteArray())
        bos.write(
            byteArrayOf(
                (pcm.size and 0xFF).toByte(),
                ((pcm.size shr 8) and 0xFF).toByte(),
                ((pcm.size shr 16) and 0xFF).toByte(),
                ((pcm.size shr 24) and 0xFF).toByte(),
            ),
        )
        bos.write(pcm)
        file.writeBytes(bos.toByteArray())
        return file
    }

    @Test
    fun `locateData finds the data chunk in a canonical 44-byte header`() {
        val pcm = ByteArray(640) { it.toByte() }
        val file = writeWav(pcm)
        RandomAccessFile(file, "r").use { raf ->
            assertEquals(640L, WavPcmStream.locateData(raf))
            assertEquals(44L, raf.filePointer) // positioned at first PCM byte
        }
    }

    @Test
    fun `locateData skips foreign chunks on the way to data`() {
        val pcm = ByteArray(200) { 1 }
        val file = writeWav(
            pcm,
            extraChunks = listOf(
                "LIST" to ByteArray(13) { 9 }, // odd length -> pad byte
                "JUNK" to ByteArray(4),
            ),
        )
        RandomAccessFile(file, "r").use { raf ->
            assertEquals(200L, WavPcmStream.locateData(raf))
            val rest = ByteArray(200)
            raf.readFully(rest)
            assertTrue(rest.all { it == 1.toByte() })
        }
    }

    @Test
    fun `locateData rejects a file with no data chunk`() {
        // Valid RIFF shell, data chunk never arrives.
        val file = File(tmp.newFolder(), "empty.wav")
        file.writeBytes(WavPcm.header(16_000, 0).copyOfRange(0, 36))
        try {
            RandomAccessFile(file, "r").use { WavPcmStream.locateData(it) }
            fail("expected failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("data"))
        }
    }

    @Test
    fun `decodePcm16le converts little-endian samples`() {
        val buf = byteArrayOf(0, 0, 0xFF.toByte(), 0x7F, 0, 0x80.toByte())
        val out = FloatArray(4)
        val n = WavPcmStream.decodePcm16le(buf, buf.size, out)
        assertEquals(3, n)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(32767f / 32768f, out[1], 1e-6f)
        assertEquals(-1f, out[2], 1e-6f)
    }

    @Test
    fun `feed streams the whole take in bounded chunks`() {
        // 1.5 MB of PCM — crosses the 512 KB chunk boundary three times.
        val pcm = ByteArray(1_500_000) { (it % 251).toByte() }
        val file = writeWav(pcm)
        val chunks = mutableListOf<Int>()
        val all = mutableListOf<Float>()
        WavPcmStream.feed(file) { floats, n ->
            chunks += n
            repeat(n) { all += floats[it] }
        }
        assertEquals(pcm.size / 2, all.size)
        assertTrue(chunks.size >= 3)
        assertTrue(chunks.all { it <= WavPcmStream.CHUNK_BYTES / 2 })
        // Spot-check the decode at a chunk boundary.
        val at = 262_144
        val s = (pcm[2 * at + 1].toInt() shl 8) or (pcm[2 * at].toInt() and 0xFF)
        assertEquals(s / 32768f, all[at], 1e-6f)
    }

    @Test
    fun `feed refuses a take over the one-hour cap`() {
        // Header claims > 1 h of PCM — nothing is decoded or allocated.
        val file = File(tmp.newFolder(), "huge.wav")
        file.writeBytes(WavPcm.header(16_000, WavPcmStream.MAX_SAMPLES * 2 + 4))
        try {
            WavPcmStream.feed(file) { _, _ -> fail("no chunk should be emitted") }
            fail("expected failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("60"))
        }
    }
}
