package io.github.polarfox0317.sikboonge

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BotStreamProviderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun malformedOpaqueTokensAreRejectedWithoutOpeningAFile() {
        val malformed = Uri.parse("content://${context.packageName}.botstream/731?fileId=731")
        try {
            context.contentResolver.openFileDescriptor(malformed, "r")?.close()
            throw AssertionError("Malformed token was accepted")
        } catch (_: Exception) {
            assertTrue(true)
        }
    }
}
