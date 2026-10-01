package com.chronicle.app.data

import java.util.Base64
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * BE-24 — the AES/GCM core behind [SecretStore], exercised on the JVM with a
 * plain generated key (the AndroidKeyStore wrapper stays untested-thin).
 * Covers the round-trip, IV freshness, truncation and tag tampering — the
 * properties that make a stored blob trustworthy.
 */
class SecretStoreTest {

    private fun newCipher(): AesGcmCipher {
        val gen = KeyGenerator.getInstance("AES")
        gen.init(256)
        return AesGcmCipher(gen.generateKey())
    }

    @Test
    fun `encrypt then decrypt returns the plaintext`() {
        val cipher = newCipher()
        val sealed = cipher.encrypt("sk-or-v1-averysecretkey")
        assertEquals("sk-or-v1-averysecretkey", cipher.decrypt(sealed))
    }

    @Test
    fun `equal plaintexts seal to different blobs`() {
        // A fresh random IV per message — the wire form never leaks equality.
        val cipher = newCipher()
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"))
    }

    @Test
    fun `a tampered ciphertext is rejected`() {
        val cipher = newCipher()
        val raw = Base64.getDecoder().decode(cipher.encrypt("key material"))
        raw[raw.size - 1] = (raw.last() + 1).toByte() // flip the tag's last byte
        assertThrows(Exception::class.java) {
            cipher.decrypt(Base64.getEncoder().encodeToString(raw))
        }
    }

    @Test
    fun `a truncated blob is rejected`() {
        val cipher = newCipher()
        val sealed = cipher.encrypt("key material")
        assertThrows(Exception::class.java) {
            cipher.decrypt(sealed.dropLast(4))
        }
    }

    @Test
    fun `a blob under a different key is rejected`() {
        val blob = newCipher().encrypt("key material")
        assertThrows(Exception::class.java) { newCipher().decrypt(blob) }
    }

    @Test
    fun `non-base64 garbage is rejected`() {
        assertThrows(Exception::class.java) { newCipher().decrypt("!!!not-a-blob!!!") }
    }
}
