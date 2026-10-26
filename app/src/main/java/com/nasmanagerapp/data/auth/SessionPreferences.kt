package com.nasmanagerapp.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the last successful connection so "Stay logged in" can restore it on next launch.
 * Every value is encrypted (AES-256-GCM) with a key held in the Android Keystore, because the
 * password stored here is itself a standing credential — anyone reading it can call the TrueNAS
 * API, and log into the rest of the server, as this user. The key is non-extractable and never
 * leaves the device; only the ciphertext lands in the plain SharedPreferences file.
 *
 * Replaces `androidx.security:security-crypto` (`EncryptedSharedPreferences`), deprecated and stuck
 * on Tink 1.8.0 — see `CONNECTIVITY_TODO.md`. A session saved by that older format is deleted
 * rather than migrated, so those users log in once more after updating.
 */
class SessionPreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val key: SecretKey by lazy { getOrCreateKey() }

    init {
        deleteLegacySession(context.applicationContext)
        // The Keystore key can disappear while the file survives (e.g. a lock-screen reset on some
        // devices): drop everything rather than restore a session with an empty password.
        if (STRING_KEYS.any { prefs.contains(it) && read(it) == null }) clear()
    }

    var serverUrl: String
        get() = read(KEY_SERVER_URL) ?: ""
        set(value) = write(KEY_SERVER_URL, value)

    var username: String
        get() = read(KEY_USERNAME) ?: ""
        set(value) = write(KEY_USERNAME, value)

    var password: String
        get() = read(KEY_PASSWORD) ?: ""
        set(value) = write(KEY_PASSWORD, value)

    var isLoggedIn: Boolean
        get() = prefs.getBoolean(KEY_LOGGED_IN, false)
        set(value) = prefs.edit().putBoolean(KEY_LOGGED_IN, value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun read(name: String): String? =
        prefs.getString(name, null)?.let { decryptValue(key, name, it) }

    private fun write(name: String, value: String) {
        prefs.edit().putString(name, encryptValue(key, name, value)).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
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

    private companion object {
        const val PREFS_NAME = "truenas_session_v2"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_LOGGED_IN = "logged_in"
        val STRING_KEYS = listOf(KEY_SERVER_URL, KEY_USERNAME, KEY_PASSWORD)

        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "nasmanager_session_key"

        // What security-crypto 1.1.0 left behind: its prefs file and its default MasterKey alias.
        const val LEGACY_PREFS_NAME = "truenas_session"
        const val LEGACY_MASTER_KEY_ALIAS = "_androidx_security_master_key_"

        fun deleteLegacySession(context: Context) {
            context.deleteSharedPreferences(LEGACY_PREFS_NAME)
            try {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                    .takeIf { it.containsAlias(LEGACY_MASTER_KEY_ALIAS) }
                    ?.deleteEntry(LEGACY_MASTER_KEY_ALIAS)
            } catch (_: GeneralSecurityException) {
                // Best-effort cleanup: an orphaned, unused key entry is harmless.
            }
        }
    }
}

private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BITS = 128
private const val TRANSFORMATION = "AES/GCM/NoPadding"

/**
 * Encrypts [value] as Base64(`IV || ciphertext+tag`). The preference [name] is bound as GCM
 * associated data, so a ciphertext copied into another slot (e.g. password → username) fails to
 * decrypt instead of being read back as the wrong field.
 */
internal fun encryptValue(key: SecretKey, name: String, value: String): String {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key)
    cipher.updateAAD(name.toByteArray())
    val ciphertext = cipher.doFinal(value.toByteArray())
    return Base64.getEncoder().encodeToString(cipher.iv + ciphertext)
}

/** Reverse of [encryptValue]; null when [stored] is malformed, tampered with, or from another key. */
internal fun decryptValue(key: SecretKey, name: String, stored: String): String? = try {
    val bytes = Base64.getDecoder().decode(stored)
    if (bytes.size <= GCM_IV_BYTES) {
        null
    } else {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_BITS, bytes, 0, GCM_IV_BYTES),
        )
        cipher.updateAAD(name.toByteArray())
        String(cipher.doFinal(bytes, GCM_IV_BYTES, bytes.size - GCM_IV_BYTES))
    }
} catch (_: GeneralSecurityException) {
    null
} catch (_: IllegalArgumentException) {
    null
}
