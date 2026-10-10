package io.github.polarfox0317.sikboonge

import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreAliasMigrationTest {
    @Test
    fun credentialsMoveFromLegacyAliasAndOnlyThenDeleteIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val prefsName = "alias_credentials_$suffix"
        val oldAlias = "test.credentials.old.$suffix"
        val newAlias = "test.credentials.new.$suffix"
        val credentials = BotCredentials(184762, "synthetic-hash-$suffix", "synthetic-token-$suffix")
        val oldWriter = BotCredentialsStore(context, prefsName, oldAlias)
        val newReader = BotCredentialsStore(context, prefsName, newAlias, oldAlias)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        try {
            oldWriter.save(credentials)
            assertTrue(keyStore.containsAlias(oldAlias))
            assertEquals(credentials, newReader.load())
            assertTrue(keyStore.containsAlias(newAlias))
            assertFalse(keyStore.containsAlias(oldAlias))
        } finally {
            oldWriter.clear()
            newReader.clear()
        }
    }

    @Test
    fun damagedLegacyCredentialCiphertextKeepsOriginalRecordAndAlias() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val prefsName = "alias_credentials_damaged_$suffix"
        val oldAlias = "test.credentials.old.$suffix"
        val newAlias = "test.credentials.new.$suffix"
        val oldWriter = BotCredentialsStore(context, prefsName, oldAlias)
        val newReader = BotCredentialsStore(context, prefsName, newAlias, oldAlias)
        val preferences = context.getSharedPreferences(prefsName, 0)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        try {
            oldWriter.save(BotCredentials(184762, "synthetic-hash", "synthetic-token"))
            val original = requireNotNull(preferences.getString("encrypted_credentials_v1", null))
            val damaged = original.dropLast(1) + if (original.last() == 'A') 'B' else 'A'
            assertTrue(preferences.edit().putString("encrypted_credentials_v1", damaged).commit())

            assertTrue(newReader.load() == null)
            assertEquals(damaged, preferences.getString("encrypted_credentials_v1", null))
            assertTrue(keyStore.containsAlias(oldAlias))
        } finally {
            oldWriter.clear()
            newReader.clear()
        }
    }

    @Test
    fun mixedPrivateStateAliasesMigrateAllFieldsWithoutChangingTdlibKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val prefsName = "alias_private_$suffix"
        val oldAlias = "test.private.old.$suffix"
        val newAlias = "test.private.new.$suffix"
        val oldWriter = BotPrivateStore(context, prefsName, oldAlias)
        val mixedWriter = BotPrivateStore(context, prefsName, newAlias, oldAlias)
        val newReader = BotPrivateStore(context, prefsName, newAlias, oldAlias)
        val row = BotVideo(
            fileId = 21,
            chatId = 7001L,
            messageId = 41L,
            name = "synthetic.mp4",
            bytes = 123L,
            durationSeconds = 5,
            date = 1_700_000_000L,
            remoteFileId = "synthetic-remote-id",
        )
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        try {
            oldWriter.pendingNonce = "synthetic-nonce"
            oldWriter.pendingCreatedMs = 1_700_000_000L
            oldWriter.pairedUserId = 7001L
            oldWriter.pairedChatId = -1007001L
            oldWriter.videos = listOf(row)
            val databaseKeyBefore = oldWriter.databaseKey()
            assertEquals(32, databaseKeyBefore.size)

            // Exercise a legitimate mixed-alias state from an interrupted/partial writer.
            mixedWriter.pendingNonce = "updated-synthetic-nonce"
            assertTrue(keyStore.containsAlias(oldAlias))
            assertTrue(keyStore.containsAlias(newAlias))

            assertEquals("updated-synthetic-nonce", newReader.pendingNonce)
            assertEquals(1_700_000_000L, newReader.pendingCreatedMs)
            assertEquals(7001L, newReader.pairedUserId)
            assertEquals(-1007001L, newReader.pairedChatId)
            assertEquals(listOf(row), newReader.videos)
            val databaseKeyAfter = newReader.databaseKey()
            assertTrue(databaseKeyBefore.contentEquals(databaseKeyAfter))
            databaseKeyBefore.fill(0)
            databaseKeyAfter.fill(0)
            assertFalse(keyStore.containsAlias(oldAlias))
            assertTrue(keyStore.containsAlias(newAlias))
        } finally {
            oldWriter.clear()
            mixedWriter.clear()
            newReader.clear()
        }
    }

    @Test
    fun currentOnlyCredentialRecordIsNotRewritten() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val prefsName = "alias_current_only_$suffix"
        val currentAlias = "test.credentials.current.$suffix"
        val store = BotCredentialsStore(context, prefsName, currentAlias)
        val preferences = context.getSharedPreferences(prefsName, 0)
        val credentials = BotCredentials(184762, "synthetic-hash-$suffix", "synthetic-token-$suffix")

        try {
            store.save(credentials)
            val ciphertext = preferences.getString("encrypted_credentials_v1", null)
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertFalse(keyStore.containsAlias("$currentAlias.legacy"))
            assertEquals(credentials, store.load())
            assertEquals(ciphertext, preferences.getString("encrypted_credentials_v1", null))
        } finally {
            store.clear()
        }
    }

    @Test
    fun interruptedPromotedCommitIsFinalizedOnNextRead() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val prefsName = "alias_promoted_recovery_$suffix"
        val oldAlias = "test.credentials.old.$suffix"
        val newAlias = "test.credentials.new.$suffix"
        val oldWriter = BotCredentialsStore(context, prefsName, oldAlias)
        val newReader = BotCredentialsStore(context, prefsName, newAlias, oldAlias)
        val preferences = context.getSharedPreferences(prefsName, 0)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val credentials = BotCredentials(184762, "synthetic-hash-$suffix", "synthetic-token-$suffix")

        try {
            oldWriter.save(credentials)
            val failingPreferences = FailWhenRemovingBackup(preferences)
            val failure = runCatching {
                KeystoreAliasMigration.migrateAndRead(
                    failingPreferences, newAlias, oldAlias, setOf("encrypted_credentials_v1"),
                )
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(preferences.contains("__keystore_alias_migration_backup_v1"))
            assertTrue(keyStore.containsAlias(oldAlias))
            assertEquals(credentials, newReader.load())
            assertFalse(preferences.contains("__keystore_alias_migration_backup_v1"))
            assertFalse(keyStore.containsAlias(oldAlias))
        } finally {
            oldWriter.clear()
            newReader.clear()
        }
    }

    @Test
    fun interruptedRollbackSnapshotIsRestoredBeforeRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val prefsName = "alias_rollback_recovery_$suffix"
        val oldAlias = "test.credentials.old.$suffix"
        val newAlias = "test.credentials.new.$suffix"
        val oldWriter = BotCredentialsStore(context, prefsName, oldAlias)
        val newReader = BotCredentialsStore(context, prefsName, newAlias, oldAlias)
        val preferences = context.getSharedPreferences(prefsName, 0)
        val credentials = BotCredentials(184762, "synthetic-hash-$suffix", "synthetic-token-$suffix")

        try {
            oldWriter.save(credentials)
            val original = requireNotNull(preferences.getString("encrypted_credentials_v1", null))
            val backup = org.json.JSONObject().put("encrypted_credentials_v1", original).toString()
            assertTrue(preferences.edit().putString("__keystore_alias_migration_backup_v1", backup).commit())
            assertEquals(credentials, newReader.load())
            assertFalse(preferences.contains("__keystore_alias_migration_backup_v1"))
        } finally {
            oldWriter.clear()
            newReader.clear()
        }
    }

    @Test
    fun failedPreferenceCommitRestoresCiphertextAndRetrySucceeds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val prefsName = "alias_commit_failure_$suffix"
        val oldAlias = "test.credentials.old.$suffix"
        val newAlias = "test.credentials.new.$suffix"
        val oldWriter = BotCredentialsStore(context, prefsName, oldAlias)
        val newReader = BotCredentialsStore(context, prefsName, newAlias, oldAlias)
        val preferences = context.getSharedPreferences(prefsName, 0)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val credentials = BotCredentials(184762, "synthetic-hash-$suffix", "synthetic-token-$suffix")

        try {
            oldWriter.save(credentials)
            val original = requireNotNull(preferences.getString("encrypted_credentials_v1", null))
            val failure = runCatching {
                KeystoreAliasMigration.migrateAndRead(
                    FailAfterApplyingCommit(preferences, failOnCommit = 2),
                    newAlias,
                    oldAlias,
                    setOf("encrypted_credentials_v1"),
                )
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(original, preferences.getString("encrypted_credentials_v1", null))
            assertTrue(keyStore.containsAlias(oldAlias))
            assertEquals(credentials, newReader.load())
            assertFalse(keyStore.containsAlias(oldAlias))
        } finally {
            oldWriter.clear()
            newReader.clear()
        }
    }

    private class FailWhenRemovingBackup(private val delegate: SharedPreferences) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor = WrappedEditor(delegate.edit())

        private inner class WrappedEditor(private val editor: SharedPreferences.Editor) : SharedPreferences.Editor by editor {
            private var removesBackup = false

            override fun remove(key: String): SharedPreferences.Editor {
                if (key == "__keystore_alias_migration_backup_v1") removesBackup = true
                editor.remove(key)
                return this
            }

            override fun commit(): Boolean = if (removesBackup) false else editor.commit()
        }
    }

    private class FailAfterApplyingCommit(
        private val delegate: SharedPreferences,
        private val failOnCommit: Int,
    ) : SharedPreferences by delegate {
        private var commits = 0
        override fun edit(): SharedPreferences.Editor = WrappedEditor(delegate.edit())

        private inner class WrappedEditor(private val editor: SharedPreferences.Editor) : SharedPreferences.Editor by editor {
            override fun commit(): Boolean {
                commits++
                val persisted = editor.commit()
                return if (commits == failOnCommit) false else persisted
            }

            override fun putString(key: String, value: String?): SharedPreferences.Editor {
                editor.putString(key, value)
                return this
            }

            override fun remove(key: String): SharedPreferences.Editor {
                editor.remove(key)
                return this
            }
        }
    }
}
