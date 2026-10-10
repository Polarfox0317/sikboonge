package io.github.polarfox0317.sikboonge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal sealed interface BotCredentialsReadResult {
    data object Missing : BotCredentialsReadResult
    data class Available(val credentials: BotCredentials) : BotCredentialsReadResult
    data object Unreadable : BotCredentialsReadResult
}

/** Encrypts the bot token and Telegram app credentials with an AndroidKeyStore AES key. */
class BotCredentialsStore internal constructor(
    context: Context,
    preferencesName: String = PREFS_NAME,
    private val keyAlias: String = KEY_ALIAS,
) {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    @Synchronized
    fun save(credentials: BotCredentials) {
        require(credentials.apiId > 0) { "apiId must be positive" }
        require(credentials.apiHash.isNotBlank() && credentials.token.isNotBlank()) { "Credentials must not be blank" }
        val plaintext = JSONObject()
            .put("apiId", credentials.apiId)
            .put("apiHash", credentials.apiHash)
            .put("token", credentials.token)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encrypted = cipher.doFinal(plaintext)
            val packed = ByteArray(cipher.iv.size + encrypted.size)
            System.arraycopy(cipher.iv, 0, packed, 0, cipher.iv.size)
            System.arraycopy(encrypted, 0, packed, cipher.iv.size, encrypted.size)
            check(preferences.edit().putString(CREDENTIALS_KEY, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
                "Could not persist encrypted bot credentials"
            }
        } finally {
            plaintext.fill(0)
        }
    }

    @Synchronized
    fun load(): BotCredentials? = (loadResult() as? BotCredentialsReadResult.Available)?.credentials

    /** Distinguishes a new setup from stored credentials that the device cannot currently read. */
    @Synchronized
    internal fun loadResult(): BotCredentialsReadResult {
        val encoded = preferences.getString(CREDENTIALS_KEY, null) ?: return BotCredentialsReadResult.Missing
        return try {
            val key = getExistingKey() ?: return BotCredentialsReadResult.Unreadable
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > GCM_IV_BYTES) { "Encrypted credential record is truncated" }
            val iv = packed.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = packed.copyOfRange(GCM_IV_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext)
            try {
                val json = JSONObject(String(plaintext, StandardCharsets.UTF_8))
                BotCredentialsReadResult.Available(
                    BotCredentials(json.getInt("apiId"), json.getString("apiHash"), json.getString("token")),
                )
            } finally {
                plaintext.fill(0)
            }
        } catch (_: Exception) {
            // Keep the ciphertext and KeyStore alias so a temporary read failure cannot erase auth.
            BotCredentialsReadResult.Unreadable
        }
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(CREDENTIALS_KEY).commit()
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        getExistingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun getExistingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(keyAlias, null) as? SecretKey
    }

    private companion object {
        const val PREFS_NAME = "bot_credentials_secure"
        const val CREDENTIALS_KEY = "encrypted_credentials_v1"
        const val KEY_ALIAS = "io.github.polarfox0317.sikboonge.bot.credentials.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
