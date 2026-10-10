package io.github.polarfox0317.sikboonge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversionPresentationPolicyTest {
    private val result = AudioResult(
        path = "/private/audio/sample.m4a",
        name = "sample.m4a",
        mime = "audio/mp4",
        bytes = 128L,
        date = 1L,
    )

    @Test
    fun localConversionProgressAndCompletionRemainVisible() {
        val running = ConversionState(
            origin = ConversionOrigin.LOCAL_CONVERT,
            running = true,
            stage = "오디오 추출 중",
        )
        val completed = ConversionState(
            origin = ConversionOrigin.LOCAL_CONVERT,
            stage = "추출 완료",
            result = result,
        )

        assertTrue(ConversionPresentationPolicy.showLocalProgress(running))
        assertFalse(ConversionPresentationPolicy.showLocalProgress(completed))
        assertSame(result, ConversionPresentationPolicy.localResult(completed))
    }

    @Test
    fun botSaveDoesNotLeakProgressOrResultIntoLocalConversionSurface() {
        val running = ConversionState(
            origin = ConversionOrigin.BOT_SAVE,
            running = true,
            stage = "Telegram 오디오 저장 중",
        )
        val completed = ConversionState(
            origin = ConversionOrigin.BOT_SAVE,
            stage = "추출 완료",
            result = result,
            botSaveCompletionId = 42L,
        )

        assertFalse(ConversionPresentationPolicy.showLocalProgress(running))
        assertFalse(ConversionPresentationPolicy.showLocalProgress(completed))
        assertNull(ConversionPresentationPolicy.localResult(running))
        assertNull(ConversionPresentationPolicy.localResult(completed))
        assertEquals(42L, completed.botSaveCompletionId)
    }
}
