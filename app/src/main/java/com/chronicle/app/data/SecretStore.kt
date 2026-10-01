package com.chronicle.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val TAG = "SecretStore"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "chronicle_secret_v1"
private const val GCM_TAG_BITS = 128
private const val IV_BYTES = 12 // 96-bit nonce — the GCM-safe size

/**
 * AES/GCM over a caller-supplied key — pure JCA so the round-trip (and the
 * tag check that catches tampering) runs in plain JVM unit tests; the
 * AndroidKeyStore machinery stays out of this class.
 *
 * Wire format: Base64( iv ‖ ciphertext‖tag ) — a fresh random 12-byte IV
 * per message, so equal plaintexts never seal to equal blobs.
 */
class AesGcmCipher(private val key: SecretKey) {

    fun encrypt(plain: String): String {
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + sealed)
    }

    /** Reverses [encrypt]; throws on bad base64, truncation, or tag mismatch. */
    fun decrypt(blob: String): String {
        val raw = Base64.getDecoder().decode(blob)
        require(raw.size > IV_BYTES) { "sealed blob too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_BITS, raw.copyOfRange(0, IV_BYTES)),
        )
        return String(cipher.doFinal(raw.copyOfRange(IV_BYTES, raw.size)), Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

/**
 * Thin Android-Keystore holder for the secret-sealing key. The AES-256 key
 * is minted once under [KEY_ALIAS] (GCM, no padding, ENCRYPT|DECRYPT) and
 * never leaves the keystore — this class only hands plaintext/ciphertext
 * pairs to [AesGcmCipher].
 */
class SecretStore private constructor(private val cipher: AesGcmCipher) {

    fun encrypt(plain: String): String = cipher.encrypt(plain)
    fun decrypt(blob: String): String = cipher.decrypt(blob)

    companion object {
        /**
         * Loads the existing key or mints it on first use. Returns null when
         * the keystore itself fails (locked device edge cases, OEM builds) —
         * callers must keep working with plaintext prefs in that case rather
         * than dropping the user's settings.
         */
        fun create(): SecretStore? = runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val key =
                (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                    ?: generateKey()
            SecretStore(AesGcmCipher(key))
        }.onFailure {
            Log.w(TAG, "AndroidKeyStore unavailable — secrets stay plaintext", it)
        }.getOrNull()

        private fun generateKey(): SecretKey {
            val generator =
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
