package io.github.polarfox0317.sikboonge

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TdlibNativeTest {
    @Test fun nativeClientStartsWithoutCredentialsAndAcknowledgesClosed() {
        Client.execute(TdApi.SetLogStream(TdApi.LogStreamEmpty()))
        Client.execute(TdApi.SetLogVerbosityLevel(0))
        val version = Client.execute(TdApi.GetOption("version"))
        assertTrue("Pinned TDLib JNI did not provide its version", version is TdApi.OptionValueString && version.value.isNotBlank())
        val parameters = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val client = Client.create({ value ->
            if (value is TdApi.UpdateAuthorizationState) when (value.authorizationState) {
                is TdApi.AuthorizationStateWaitTdlibParameters -> parameters.countDown()
                is TdApi.AuthorizationStateClosed -> closed.countDown()
            }
        }, null, null)
        try {
            assertTrue("Native client never requested parameters", parameters.await(10, TimeUnit.SECONDS))
        } finally {
            client.send(TdApi.Close()) { }
            assertTrue("Native client did not acknowledge AUTH_CLOSED", closed.await(20, TimeUnit.SECONDS))
        }
    }
}
