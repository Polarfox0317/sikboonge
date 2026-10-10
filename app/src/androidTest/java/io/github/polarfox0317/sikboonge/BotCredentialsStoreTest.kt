package io.github.polarfox0317.sikboonge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BotCredentialsStoreTest {
    @Test
    fun encryptsRoundTripsAndClearsSyntheticCredentials() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val preferencesName = "bot_credentials_test_$suffix"
        val keyAlias = "io.github.polarfox0317.sikboonge.bot.credentials.test.$suffix"
        val store = BotCredentialsStore(context, preferencesName, keyAlias)
        val credentials = BotCredentials(
            apiId = 184762,
            apiHash = "synthetic-api-hash-$suffix",
            token = "synthetic-bot-token-$suffix",
        )

        try {
            store.save(credentials)
            assertEquals(credentials, store.load())
            val storedValue = context.getSharedPreferences(preferencesName, 0)
                .getString("encrypted_credentials_v1", null)
            requireNotNull(storedValue)
            assertFalse(storedValue.contains(credentials.apiHash))
            assertFalse(storedValue.contains(credentials.token))

            store.clear()
            assertNull(store.load())
        } finally {
            store.clear()
        }
    }

    @Test
    fun unreadableCredentialsKeepCiphertextAndOriginalKeyUsableForRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val preferencesName = "bot_credentials_recovery_test_$suffix"
        val keyAlias = "io.github.polarfox0317.sikboonge.bot.credentials.recovery.test.$suffix"
        val store = BotCredentialsStore(context, preferencesName, keyAlias)
        val readerWithUnavailableKey = BotCredentialsStore(context, preferencesName, "$keyAlias.unavailable")
        val credentials = BotCredentials(
            apiId = 184762,
            apiHash = "synthetic-api-hash-$suffix",
            token = "synthetic-bot-token-$suffix",
        )
        val preferences = context.getSharedPreferences(preferencesName, 0)

        try {
            store.save(credentials)
            val encryptedBeforeRead = preferences.getString("encrypted_credentials_v1", null)
            assertNotNull(encryptedBeforeRead)

            assertEquals(BotCredentialsReadResult.Unreadable, readerWithUnavailableKey.loadResult())
            assertEquals(encryptedBeforeRead, preferences.getString("encrypted_credentials_v1", null))
            assertTrue(preferences.contains("encrypted_credentials_v1"))
            assertEquals(credentials, store.load())
        } finally {
            store.clear()
            readerWithUnavailableKey.clear()
        }
    }
}
