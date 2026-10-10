package io.github.polarfox0317.sikboonge

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Re-encrypts known preference records under the current AndroidKeyStore alias without losing old data. */
internal object KeystoreAliasMigration {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val BACKUP_KEY = "__keystore_alias_migration_backup_v1"
    private const val STAGE_PREFIX = "__keystore_alias_migration_stage_v1_"
    private val lock = Any()

    fun <T> withLock(block: () -> T): T = synchronized(lock, block)

    /** Strict current-alias reads. Legacy alias migration stays below for a deliberate manual rollback only. */
    fun readCurrentOnly(
        preferences: SharedPreferences,
        currentAlias: String,
        encryptedFields: Set<String>,
        validatePlaintext: (String, ByteArray) -> Unit = { _, _ -> },
    ): Map<String, ByteArray> = withLock {
        // A pending migration snapshot means storage is ambiguous. Preserve it and fail closed.
        require(!preferences.contains(BACKUP_KEY) && preferences.all.keys.none { it.startsWith(STAGE_PREFIX) }) {
            "Encrypted preference migration recovery is required."
        }
        require(preferences.all.keys.all { it in encryptedFields }) {
            "Encrypted preference store contains an unknown field."
        }
        val originals = encryptedFields.mapNotNull { name ->
            preferences.getString(name, null)?.let { name to it }
        }.toMap()
        if (originals.isEmpty()) return@withLock emptyMap()
        val key = getExistingKey(currentAlias)
            ?: throw IllegalStateException("Current encrypted preference key is unavailable.")
        val plaintext = LinkedHashMap<String, ByteArray>(originals.size)
        try {
            originals.forEach { (name, encoded) ->
                val packed = Base64.decode(encoded, Base64.NO_WRAP)
                require(packed.size > IV_BYTES) { "Encrypted preference record is truncated." }
                val value = decrypt(packed, key)
                try {
                    validatePlaintext(name, value)
                    plaintext[name] = value
                } catch (error: Exception) {
                    value.fill(0)
                    throw error
                }
            }
            plaintext.mapValues { it.value.copyOf() }
        } finally {
            plaintext.values.forEach { it.fill(0) }
        }
    }

    /**
     * Legacy two-alias migration implementation, retained but disabled in production store call paths.
     * Re-enable only after explicit user approval for a supported migration release.
     * Returns owned plaintext copies; callers must clear them after decoding.
     */
    fun migrateAndRead(
        preferences: SharedPreferences,
        currentAlias: String,
        legacyAlias: String,
        encryptedFields: Set<String>,
        validatePlaintext: (String, ByteArray) -> Unit = { _, _ -> },
    ): Map<String, ByteArray> = withLock {
        require(currentAlias != legacyAlias)
        recoverInterruptedCommit(preferences, currentAlias, legacyAlias, encryptedFields, validatePlaintext)

        val allowed = encryptedFields + BACKUP_KEY
        require(preferences.all.keys.all { it in allowed || it.startsWith(STAGE_PREFIX) }) {
            "Encrypted preference store contains an unknown field."
        }
        val original = encryptedFields.mapNotNull { name ->
            preferences.getString(name, null)?.let { name to it }
        }.toMap()
        val currentKey = getExistingKey(currentAlias)
        val legacyKey = getExistingKey(legacyAlias)
        if (original.isEmpty()) {
            removeAlias(legacyAlias)
            return@withLock emptyMap()
        }

        val plaintext = LinkedHashMap<String, ByteArray>(original.size)
        val usedLegacy = mutableSetOf<String>()
        try {
            original.forEach { (name, encoded) ->
                val packed = Base64.decode(encoded, Base64.NO_WRAP)
                require(packed.size > IV_BYTES) { "Encrypted preference record is truncated." }
                val decoded = decryptWithAvailableKey(packed, currentKey, legacyKey)
                validatePlaintext(name, decoded.first)
                plaintext[name] = decoded.first
                if (decoded.second) usedLegacy += name
            }

            val existingLegacy = legacyKey != null
            if (currentKey != null && usedLegacy.isEmpty() && !existingLegacy) {
                return@withLock plaintext.mapValues { it.value.copyOf() }
            }

            val targetKey = currentKey ?: createKey(currentAlias)
            val staged = plaintext.mapValues { (_, value) -> encrypt(value, targetKey) }
            val stageEditor = preferences.edit()
            staged.forEach { (name, value) -> stageEditor.putString(stageName(name), value) }
            if (!stageEditor.commit()) {
                clearStaging(preferences, encryptedFields)
                error("Could not stage encrypted preference migration.")
            }
            staged.forEach { (name, encoded) ->
                val verified = decrypt(Base64.decode(encoded, Base64.NO_WRAP), targetKey)
                check(verified.contentEquals(plaintext.getValue(name))) { "Staged encrypted preference verification failed." }
                verified.fill(0)
            }

            val backup = JSONObject().apply { original.forEach { (name, value) -> put(name, value) } }.toString()
            val commit = preferences.edit().putString(BACKUP_KEY, backup)
            staged.forEach { (name, value) -> commit.putString(name, value).remove(stageName(name)) }
            if (!commit.commit()) {
                restoreOriginal(preferences, original, encryptedFields, backup)
                error("Could not commit encrypted preference migration.")
            }

            try {
                staged.forEach { (name, _) ->
                    val encoded = preferences.getString(name, null) ?: error("Migrated preference is missing.")
                    val verified = decrypt(Base64.decode(encoded, Base64.NO_WRAP), targetKey)
                    check(verified.contentEquals(plaintext.getValue(name))) { "Committed encrypted preference verification failed." }
                    verified.fill(0)
                }
            } catch (error: Exception) {
                restoreOriginal(preferences, original, encryptedFields, backup)
                throw error
            }

            check(preferences.edit().remove(BACKUP_KEY).commit()) { "Could not finalize encrypted preference migration." }
            removeAlias(legacyAlias)
            plaintext.mapValues { it.value.copyOf() }
        } finally {
            plaintext.values.forEach { it.fill(0) }
        }
    }

    private fun recoverInterruptedCommit(
        preferences: SharedPreferences,
        currentAlias: String,
        legacyAlias: String,
        encryptedFields: Set<String>,
        validatePlaintext: (String, ByteArray) -> Unit,
    ) {
        val rawBackup = preferences.getString(BACKUP_KEY, null) ?: return
        val backupJson = JSONObject(rawBackup)
        val original = encryptedFields.mapNotNull { name ->
            backupJson.optString(name, "").takeIf(String::isNotEmpty)?.let { name to it }
        }.toMap()
        require(original.isNotEmpty()) { "Encrypted preference migration backup is empty." }

        val currentKey = getExistingKey(currentAlias)
        val legacyKey = getExistingKey(legacyAlias)
        val actualNames = encryptedFields.filter(preferences::contains).toSet()
        val promoted = currentKey != null && actualNames == original.keys && original.keys.all { name ->
            runCatching {
                val prior = decryptWithAvailableKey(Base64.decode(original.getValue(name), Base64.NO_WRAP), currentKey, legacyKey).first
                val encoded = preferences.getString(name, null) ?: run { prior.fill(0); return@runCatching false }
                val value = decrypt(Base64.decode(encoded, Base64.NO_WRAP), currentKey)
                try {
                    validatePlaintext(name, prior)
                    validatePlaintext(name, value)
                    prior.contentEquals(value)
                } finally {
                    prior.fill(0)
                    value.fill(0)
                }
            }.getOrDefault(false)
        }
        if (promoted) {
            val editor = preferences.edit().remove(BACKUP_KEY)
            preferences.all.keys.filter { it.startsWith(STAGE_PREFIX) }.forEach(editor::remove)
            check(editor.commit()) { "Could not finalize interrupted encrypted preference migration." }
            removeAlias(legacyAlias)
        } else {
            // Confirm the rollback snapshot is still decryptable before allowing another migration attempt.
            original.forEach { (name, encoded) ->
                val packed = Base64.decode(encoded, Base64.NO_WRAP)
                require(packed.size > IV_BYTES)
                val decrypted = decryptWithAvailableKey(packed, currentKey, legacyKey).first
                validatePlaintext(name, decrypted)
                decrypted.fill(0)
            }
            restoreOriginal(preferences, original, encryptedFields, rawBackup)
        }
    }

    private fun restoreOriginal(
        preferences: SharedPreferences,
        original: Map<String, String>,
        encryptedFields: Set<String>,
        backup: String,
    ) {
        val editor = preferences.edit().putString(BACKUP_KEY, backup)
        original.forEach { (name, encoded) -> editor.putString(name, encoded) }
        encryptedFields.filter { it !in original }.forEach(editor::remove)
        preferences.all.keys.filter { it.startsWith(STAGE_PREFIX) }.forEach(editor::remove)
        val committed = editor.commit()
        check(committed) { "Could not persist encrypted preference snapshot rollback." }
        check(
            preferences.getString(BACKUP_KEY, null) == backup &&
                original.all { (name, encoded) -> preferences.getString(name, null) == encoded } &&
                encryptedFields.filter { it !in original }.none(preferences::contains),
        ) { "Could not restore encrypted preference snapshot." }
    }

    private fun clearStaging(preferences: SharedPreferences, fields: Set<String>) {
        val editor = preferences.edit()
        fields.forEach { editor.remove(stageName(it)) }
        editor.commit()
    }

    private fun decryptWithAvailableKey(
        packed: ByteArray,
        currentKey: SecretKey?,
        legacyKey: SecretKey?,
    ): Pair<ByteArray, Boolean> {
        if (currentKey != null) runCatching { decrypt(packed, currentKey) }.getOrNull()?.let { return it to false }
        if (legacyKey != null) runCatching { decrypt(packed, legacyKey) }.getOrNull()?.let { return it to true }
        throw IllegalStateException("Could not decrypt an encrypted preference record with available aliases.")
    }

    private fun encrypt(plaintext: ByteArray, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plaintext)
        val packed = cipher.iv + encrypted
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(packed: ByteArray, key: SecretKey): ByteArray {
        require(packed.size > IV_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, packed.copyOfRange(0, IV_BYTES)))
        return cipher.doFinal(packed.copyOfRange(IV_BYTES, packed.size))
    }

    private fun stageName(name: String) = "$STAGE_PREFIX$name"

    private fun getExistingKey(alias: String): SecretKey? {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return store.getKey(alias, null) as? SecretKey
    }

    private fun createKey(alias: String): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun removeAlias(alias: String) {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (store.containsAlias(alias)) runCatching { store.deleteEntry(alias) }
    }
}
