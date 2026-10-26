package com.nasmanagerapp.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The Android Keystore isn't available on the JVM, so these exercise [encryptValue]/[decryptValue]
 * with a plain software AES-256 key — same cipher, same format, only the key's storage differs.
 */
class SessionCryptoTest {

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun `round-trips a value, including non-ascii and empty strings`() {
        val key = newKey()
        for (value in listOf("s3cr3t-pässwörd 🔑", "")) {
            assertEquals(value, decryptValue(key, "password", encryptValue(key, "password", value)))
        }
    }

    @Test
    fun `uses a fresh iv each time, so the same value never produces the same ciphertext`() {
        val key = newKey()
        assertNotEquals(encryptValue(key, "password", "same"), encryptValue(key, "password", "same"))
    }

    @Test
    fun `refuses a ciphertext moved to another preference slot`() {
        val key = newKey()
        assertNull(decryptValue(key, "username", encryptValue(key, "password", "secret")))
    }

    @Test
    fun `returns null for a value encrypted with another key`() {
        assertNull(decryptValue(newKey(), "password", encryptValue(newKey(), "password", "secret")))
    }

    @Test
    fun `returns null for a tampered ciphertext`() {
        val key = newKey()
        val bytes = Base64.getDecoder().decode(encryptValue(key, "password", "secret"))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertNull(decryptValue(key, "password", Base64.getEncoder().encodeToString(bytes)))
    }

    @Test
    fun `returns null for malformed or truncated input instead of throwing`() {
        val key = newKey()
        assertNull(decryptValue(key, "password", "not base64 !!"))
        assertNull(decryptValue(key, "password", ""))
        assertNull(decryptValue(key, "password", Base64.getEncoder().encodeToString(ByteArray(12))))
    }
}
