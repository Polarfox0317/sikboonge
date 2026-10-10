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
    legacyAlias: String? = null,
) {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    // Read fallback is disabled; the legacy alias is retained only for explicit clear().
    private val legacyKeyAlias = legacyAlias ?: if (keyAlias == KEY_ALIAS) LEGACY_KEY_ALIAS else "$keyAlias.legacy"

    @Synchronized
    fun save(credentials: BotCredentials) = KeystoreAliasMigration.withLock {
        require(credentials.apiId > 0) { "apiId must be positive" }
        require(credentials.apiHash.isNotBlank() && credentials.token.isNotBlank()) { "Credentials must not be blank" }
        // Disabled legacy migration call; re-enable only for a confirmed compatibility release:
        // KeystoreAliasMigration.migrateAndRead(preferences, keyAlias, legacyKeyAlias, setOf(CREDENTIALS_KEY), ::validateCredentials)
        KeystoreAliasMigration.readCurrentOnly(preferences, keyAlias, setOf(CREDENTIALS_KEY), ::validateCredentials)
            .values.forEach { it.fill(0) }
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
    internal fun loadResult(): BotCredentialsReadResult = KeystoreAliasMigration.withLock {
        val current = try {
            // Disabled legacy migration call; re-enable only for a confirmed compatibility release:
            // KeystoreAliasMigration.migrateAndRead(preferences, keyAlias, legacyKeyAlias, setOf(CREDENTIALS_KEY), ::validateCredentials)
            KeystoreAliasMigration.readCurrentOnly(preferences, keyAlias, setOf(CREDENTIALS_KEY), ::validateCredentials)
        } catch (_: Exception) {
            return@withLock BotCredentialsReadResult.Unreadable
        }
        val plaintext = current[CREDENTIALS_KEY] ?: run {
            current.values.forEach { it.fill(0) }
            return@withLock BotCredentialsReadResult.Missing
        }
        try {
            val json = JSONObject(String(plaintext, StandardCharsets.UTF_8))
            BotCredentialsReadResult.Available(
                BotCredentials(json.getInt("apiId"), json.getString("apiHash"), json.getString("token")),
            )
        } catch (_: Exception) {
            BotCredentialsReadResult.Unreadable
        } finally {
            current.values.forEach { it.fill(0) }
        }
    }

    @Synchronized
    fun clear() = KeystoreAliasMigration.withLock {
        check(preferences.edit().remove(CREDENTIALS_KEY).commit()) { "Could not clear encrypted bot credentials" }
        removeAlias(keyAlias)
        removeAlias(legacyKeyAlias)
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

    private fun validateCredentials(name: String, plaintext: ByteArray) {
        check(name == CREDENTIALS_KEY)
        val json = JSONObject(String(plaintext, StandardCharsets.UTF_8))
        require(json.getInt("apiId") > 0)
        require(json.getString("apiHash").isNotBlank() && json.getString("token").isNotBlank())
    }

    private fun removeAlias(alias: String) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        check(!KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.containsAlias(alias)) {
            "Could not remove encrypted bot credential key"
        }
    }

    private companion object {
        const val PREFS_NAME = "bot_credentials_secure"
        const val CREDENTIALS_KEY = "encrypted_credentials_v1"
        const val KEY_ALIAS = "io.github.polarfox0317.sikboonge.bot.credentials.v1"
        const val LEGACY_KEY_ALIAS = "app.videoaudio.bot.credentials.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
