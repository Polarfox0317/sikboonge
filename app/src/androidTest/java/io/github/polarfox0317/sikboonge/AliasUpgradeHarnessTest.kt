package io.github.polarfox0317.sikboonge

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit emulator-only seed stage for testing an app update across the package's historical aliases. */
@RunWith(AndroidJUnit4::class)
class AliasUpgradeSeedHarnessTest {
    @Test
    fun seedSyntheticLegacyRecordsOnlyWhenExplicitlyRequested() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("aliasUpgradePhase") ?: return
        if (phase != "seed") return
        requireEmulatorOptIn(args.getString("aliasUpgradeTarget"))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.github.polarfox0317.sikboonge", context.packageName)
        val credentialPrefs = context.getSharedPreferences("bot_credentials_secure", 0)
        val privatePrefs = context.getSharedPreferences("telegram_bot_private_v1", 0)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val oldCredentialAlias = "app.videoaudio.bot.credentials.v1"
        val oldPrivateAlias = "app.videoaudio.telegram.bot.private.v1"
        val newCredentialAlias = "io.github.polarfox0317.sikboonge.bot.credentials.v1"
        val newPrivateAlias = "io.github.polarfox0317.sikboonge.telegram.bot.private.v1"
        require(credentialPrefs.all.isEmpty() && privatePrefs.all.isEmpty()) { "Upgrade harness requires an empty test app." }
        require(listOf(oldCredentialAlias, oldPrivateAlias, newCredentialAlias, newPrivateAlias).none(keyStore::containsAlias)) {
            "Upgrade harness aliases already exist; refusing to change app data."
        }

        val credential = JSONObject()
            .put("apiId", 184762)
            .put("apiHash", "synthetic-upgrade-hash")
            .put("token", "synthetic-upgrade-token")
            .toString()
            .toByteArray(Charsets.UTF_8)
        val databaseKey = ByteArray(32) { (it + 1).toByte() }
        val state = linkedMapOf(
            "encrypted_tdlib_database_key" to databaseKey,
            "encrypted_recent_videos" to "{\"version\":3,\"videos\":[],\"tombstones\":[]}".toByteArray(),
            "nonce" to "synthetic-upgrade-nonce".toByteArray(),
            "nonce_created" to "1700000000".toByteArray(),
            "user" to "7001".toByteArray(),
            "chat" to "-1007001".toByteArray(),
        )
        try {
            check(
                credentialPrefs.edit()
                    .putString("encrypted_credentials_v1", encrypt(oldCredentialAlias, credential))
                    .commit(),
            )
            val privateEditor = privatePrefs.edit()
            state.forEach { (name, bytes) -> privateEditor.putString(name, encrypt(oldPrivateAlias, bytes)) }
            check(privateEditor.commit())
        } finally {
            credential.fill(0)
            databaseKey.fill(0)
            state.values.forEach { it.fill(0) }
        }
    }

    private fun requireEmulatorOptIn(target: String?) {
        assumeTrue(
            Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk_gphone", ignoreCase = true),
        )
        require(target == "io.github.polarfox0317.sikboonge") {
            "Pass -e aliasUpgradeTarget io.github.polarfox0317.sikboonge to opt in."
        }
    }

    private fun encrypt(alias: String, plaintext: ByteArray): String {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey(alias, null) as? SecretKey ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }
            .generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plaintext), Base64.NO_WRAP)
    }
}

/** Verify stage to run only after updating the seeded emulator app to the new version. */
@RunWith(AndroidJUnit4::class)
class AliasUpgradeVerifyHarnessTest {
    @Test
    fun verifySyntheticRecordsSurviveUpgradeAndLegacyAliasesAreRemoved() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("aliasUpgradePhase") ?: return
        if (phase != "verify") return
        assumeTrue(Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk_gphone", ignoreCase = true))
        require(args.getString("aliasUpgradeTarget") == "io.github.polarfox0317.sikboonge") {
            "Pass -e aliasUpgradeTarget io.github.polarfox0317.sikboonge to opt in."
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.github.polarfox0317.sikboonge", context.packageName)
        assertEquals(
            BotCredentials(184762, "synthetic-upgrade-hash", "synthetic-upgrade-token"),
            BotCredentialsStore(context).load(),
        )
        val privateStore = BotPrivateStore(context)
        assertEquals("synthetic-upgrade-nonce", privateStore.pendingNonce)
        assertEquals(1_700_000_000L, privateStore.pendingCreatedMs)
        assertEquals(7001L, privateStore.pairedUserId)
        assertEquals(-1007001L, privateStore.pairedChatId)
        assertTrue(privateStore.videos.isEmpty())
        val databaseKey = privateStore.databaseKey()
        assertTrue(databaseKey.contentEquals(ByteArray(32) { (it + 1).toByte() }))
        databaseKey.fill(0)

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(keyStore.containsAlias("io.github.polarfox0317.sikboonge.bot.credentials.v1"))
        assertTrue(keyStore.containsAlias("io.github.polarfox0317.sikboonge.telegram.bot.private.v1"))
        assertFalse(keyStore.containsAlias("app.videoaudio.bot.credentials.v1"))
        assertFalse(keyStore.containsAlias("app.videoaudio.telegram.bot.private.v1"))
    }
}
