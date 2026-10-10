package io.github.polarfox0317.sikboonge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.json.JSONArray
import org.json.JSONObject

internal data class BotMessageKey(val chatId: Long, val messageId: Long)

internal object BotInboxDeletionPolicy {
    fun keys(chatId: Long, messageIds: Set<Long>): Set<BotMessageKey> =
        if (chatId <= 0L) emptySet() else messageIds.filter { it > 0L }.mapTo(mutableSetOf()) { BotMessageKey(chatId, it) }

    fun visibleRows(rows: List<BotVideo>, tombstones: Set<BotMessageKey>): List<BotVideo> =
        rows.filterNot { BotMessageKey(it.chatId, it.messageId) in tombstones }

    fun refreshRows(
        rows: List<BotVideo>,
        chatId: Long,
        tombstones: Set<BotMessageKey>,
        limit: Int,
    ): List<BotVideo> = if (chatId <= 0L || limit <= 0) emptyList() else
        rows.filter { it.chatId == chatId && BotMessageKey(it.chatId, it.messageId) !in tombstones }.take(limit)
}

/** Encrypted pairing metadata and TDLib database key; kept separate from the credential screen store. */
internal class BotPrivateStore(
    context: Context,
    preferencesName: String = PREFS,
    private val keyAlias: String = KEY_ALIAS,
    legacyAlias: String? = null,
) {
    private val prefs = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    // Read fallback is disabled; the legacy alias is retained only for explicit clear().
    private val legacyKeyAlias = legacyAlias ?: if (keyAlias == KEY_ALIAS) LEGACY_KEY_ALIAS else "$keyAlias.legacy"
    private data class InboxData(val videos: List<BotVideo>, val tombstones: Set<BotMessageKey>)

    var pendingNonce: String?
        get() = readString("nonce")
        set(value) = writeString("nonce", value)
    var pendingCreatedMs: Long
        get() = readLong("nonce_created")
        set(value) = writeLong("nonce_created", value)
    var pairedUserId: Long
        get() = readLong("user")
        set(value) = writeLong("user", value)
    var pairedChatId: Long
        get() = readLong("chat")
        set(value) = writeLong("chat", value)
    var videos: List<BotVideo>
        get() = synchronized(INBOX_LOCK) { readInboxStrict().videos }
        set(value) = synchronized(INBOX_LOCK) {
            val stored = readInboxStrict()
            writeInbox(BotInboxDeletionPolicy.visibleRows(value, stored.tombstones).takeLast(MAX_VIDEOS), stored.tombstones)
        }

    val deletedMessageKeys: Set<BotMessageKey>
        get() = synchronized(INBOX_LOCK) { readInboxStrict().tombstones }

    /** Commit row removal and durable tombstones together in one encrypted preference value. */
    fun removeVideos(chatId: Long, messageIds: Set<Long>): List<BotVideo> = synchronized(INBOX_LOCK) {
        val keys = BotInboxDeletionPolicy.keys(chatId, messageIds)
        if (keys.isEmpty()) return@synchronized emptyList()
        val stored = readInboxStrict()
        val removed = stored.videos.filter { BotMessageKey(it.chatId, it.messageId) in keys }
        val remaining = BotInboxDeletionPolicy.visibleRows(stored.videos, keys)
        writeInbox(remaining, stored.tombstones + keys)
        removed
    }

    /** Persist a local title override for one exact paired-chat message. */
    fun renameVideo(chatId: Long, messageId: Long, customTitle: String?): BotVideo? = synchronized(INBOX_LOCK) {
        if (chatId <= 0L || messageId <= 0L) return@synchronized null
        val key = BotMessageKey(chatId, messageId)
        val stored = readInboxStrict()
        if (key in stored.tombstones) return@synchronized null
        val index = stored.videos.indexOfFirst { it.chatId == chatId && it.messageId == messageId }
        if (index < 0) return@synchronized null
        val normalizedTitle = customTitle?.let(BotVideoTitlePolicy::normalize)
        val renamed = stored.videos[index].copy(customTitle = normalizedTitle)
        val updated = stored.videos.toMutableList().also { it[index] = renamed }
        writeInbox(updated, stored.tombstones)
        renamed
    }

    @Synchronized
    fun databaseKey(): ByteArray {
        val hadEncryptedKey = prefs.contains(KEY_DB_KEY)
        val existing = readEncryptedStrict(KEY_DB_KEY)
        if (existing != null && existing.size >= 32) return existing
        check(!hadEncryptedKey) { "The Telegram database key is damaged; the encrypted inbox and credentials were retained." }
        return ByteArray(32).also { java.security.SecureRandom().nextBytes(it); writeEncrypted(KEY_DB_KEY, it) }
    }

    @Synchronized
    fun clear() = synchronized(INBOX_LOCK) {
        KeystoreAliasMigration.withLock {
            check(prefs.edit().clear().commit()) { "Could not clear private Telegram bot state" }
            removeAlias(keyAlias)
            removeAlias(legacyKeyAlias)
        }
    }

    private fun readInboxStrict(): InboxData {
        val raw = readEncryptedStrict(KEY_VIDEOS) ?: return InboxData(emptyList(), emptySet())
        val json = String(raw, Charsets.UTF_8)
        if (json.trimStart().startsWith("[")) return InboxData(readVideoRows(JSONArray(json)), emptySet())
        val root = JSONObject(json)
        val videos = readVideoRows(root.optJSONArray("videos") ?: JSONArray())
        val tombstonesArray = root.optJSONArray("tombstones") ?: JSONArray()
        val tombstones = buildSet {
            for (i in 0 until tombstonesArray.length()) {
                val row = tombstonesArray.optJSONObject(i) ?: continue
                val chatId = row.optLong("chat", 0L)
                val messageId = row.optLong("message", 0L)
                if (chatId > 0L && messageId > 0L) add(BotMessageKey(chatId, messageId))
            }
        }
        return InboxData(BotInboxDeletionPolicy.visibleRows(videos, tombstones), tombstones)
    }

    private fun readVideoRows(array: JSONArray): List<BotVideo> =
        (0 until array.length()).map { i -> array.getJSONObject(i).let { row ->
            BotVideo(
                fileId = row.getInt("id"), chatId = row.getLong("chat"), messageId = row.getLong("message"),
                name = row.getString("name"), bytes = row.getLong("bytes"), durationSeconds = row.getInt("duration"), date = row.getLong("date"),
                remoteFileId = row.optString("remote", ""),
                mimeType = row.optString("mime", "video/mp4").take(128),
                thumbnailRemoteFileId = row.optString("thumb_remote", "").take(512),
                thumbnailFileId = row.optInt("thumb_id", 0).coerceAtLeast(0),
                thumbnailBase64 = row.optString("thumb_mini", "").take(MAX_MINI_BASE64_CHARS),
                thumbnailPath = row.optString("thumb_path", "").takeIf(String::isNotBlank)?.take(2048),
                customTitle = row.optString("custom_title", "").take(BotVideoTitlePolicy.MAX_TITLE_CHARS).takeIf(String::isNotBlank),
            )
        } }.takeLast(MAX_VIDEOS)

    private fun validateMigrationField(name: String, bytes: ByteArray) {
        val value = bytes.toString(Charsets.UTF_8)
        when (name) {
            KEY_DB_KEY -> require(bytes.size >= 32)
            KEY_VIDEOS -> {
                if (value.trimStart().startsWith("[")) readVideoRows(JSONArray(value))
                else {
                    val root = JSONObject(value)
                    readVideoRows(root.optJSONArray("videos") ?: JSONArray())
                    root.optJSONArray("tombstones") ?: JSONArray()
                }
            }
            "nonce_created", "user", "chat" -> if (value.isNotEmpty()) require(value.toLongOrNull() != null)
            "nonce" -> Unit
            else -> error("Unknown private Telegram state field")
        }
    }

    private fun writeInbox(videos: List<BotVideo>, tombstones: Set<BotMessageKey>) {
        val rows = JSONArray().apply {
            videos.takeLast(MAX_VIDEOS).forEach { video -> put(JSONObject()
                .put("id", video.fileId).put("chat", video.chatId).put("message", video.messageId)
                .put("name", video.name).put("bytes", video.bytes).put("duration", video.durationSeconds).put("date", video.date)
                .put("remote", video.remoteFileId).put("mime", video.mimeType.take(128))
                .put("thumb_remote", video.thumbnailRemoteFileId.take(512)).put("thumb_id", video.thumbnailFileId.coerceAtLeast(0))
                .put("thumb_mini", video.thumbnailBase64.take(MAX_MINI_BASE64_CHARS)).put("thumb_path", video.thumbnailPath.orEmpty().take(2048))
                .put("custom_title", video.customTitle.orEmpty().take(BotVideoTitlePolicy.MAX_TITLE_CHARS)))
            }
        }
        val deleted = JSONArray().apply {
            tombstones.sortedWith(compareBy<BotMessageKey> { it.chatId }.thenBy { it.messageId }).forEach { key ->
                put(JSONObject().put("chat", key.chatId).put("message", key.messageId))
            }
        }
        val payload = JSONObject().put("version", INBOX_SCHEMA_VERSION).put("videos", rows).put("tombstones", deleted)
        writeEncrypted(KEY_VIDEOS, payload.toString().toByteArray(Charsets.UTF_8))
    }

    private fun readString(key: String): String? = readEncryptedStrict(key)?.toString(Charsets.UTF_8)
    private fun writeString(key: String, value: String?) {
        if (value == null) prefs.edit().remove(key).commit() else writeEncrypted(key, value.toByteArray(Charsets.UTF_8))
    }
    private fun readLong(key: String): Long = readString(key)?.toLongOrNull() ?: 0L
    private fun writeLong(key: String, value: Long) = writeString(key, value.takeIf { it != 0L }?.toString())

    private fun readEncryptedStrict(name: String): ByteArray? {
        val current = try {
            // Disabled legacy migration call; re-enable only for a confirmed compatibility release:
            // KeystoreAliasMigration.migrateAndRead(prefs, keyAlias, legacyKeyAlias, ENCRYPTED_FIELDS, ::validateMigrationField)
            KeystoreAliasMigration.readCurrentOnly(prefs, keyAlias, ENCRYPTED_FIELDS, ::validateMigrationField)
        } catch (_: Exception) {
            throw IllegalStateException("Could not read encrypted private Telegram state")
        }
        val requested = current[name]
        current.filterKeys { it != name }.values.forEach { it.fill(0) }
        return requested
    }

    private fun writeEncrypted(name: String, plain: ByteArray) = KeystoreAliasMigration.withLock {
        // Refuse to write any private field while another current-alias record is unreadable.
        KeystoreAliasMigration.readCurrentOnly(prefs, keyAlias, ENCRYPTED_FIELDS, ::validateMigrationField)
            .values.forEach { it.fill(0) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plain)
        val packed = cipher.iv + encrypted
        check(prefs.edit().putString(name, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) { "Could not persist encrypted Telegram bot state" }
        if (name != KEY_DB_KEY) plain.fill(0)
    }

    private fun getOrCreateKey(): SecretKey {
        getExistingKey()?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private fun getExistingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(keyAlias, null) as? SecretKey
    }

    private fun removeAlias(alias: String) {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
        check(!KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.containsAlias(alias)) {
            "Could not remove private Telegram key"
        }
    }

    private companion object {
        val INBOX_LOCK = Any()
        const val PREFS = "telegram_bot_private_v1"
        const val KEY_ALIAS = "io.github.polarfox0317.sikboonge.telegram.bot.private.v1"
        const val LEGACY_KEY_ALIAS = "app.videoaudio.telegram.bot.private.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_DB_KEY = "encrypted_tdlib_database_key"
        const val KEY_VIDEOS = "encrypted_recent_videos"
        const val MAX_VIDEOS = 50
        const val MAX_MINI_BASE64_CHARS = 100_000
        const val INBOX_SCHEMA_VERSION = 3
        val ENCRYPTED_FIELDS = setOf(KEY_DB_KEY, KEY_VIDEOS, "nonce", "nonce_created", "user", "chat")
    }
}
