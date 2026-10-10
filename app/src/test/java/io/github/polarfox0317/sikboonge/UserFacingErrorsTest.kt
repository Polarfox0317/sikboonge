package io.github.polarfox0317.sikboonge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFacingErrorsTest {
    @Test fun ffprobeJsonTruncationBecomesTelegramSpecificGuidance() {
        val raw = "ffprobe warning: [json @ 0x7f00] Unterminated array"

        val message = UserFacingErrors.fromErrorString(raw, UserFacingErrors.Context.TELEGRAM)

        assertEquals("Telegram 영상 데이터를 끝까지 읽지 못했어요. 연결을 확인하고 다시 시도해 주세요.", message)
    }

    @Test fun absentOrBlankErrorsDoNotCreateUserFacingMessages() {
        assertNull(UserFacingErrors.fromErrorString(null))
        assertNull(UserFacingErrors.fromErrorString("   \t\n", UserFacingErrors.Context.GENERAL))
    }

    @Test fun networkTimeoutExplainsRetryWithoutExposingNativeException() {
        val raw = "java.net.SocketTimeoutException: Read timed out"

        val message = UserFacingErrors.fromErrorString(raw, UserFacingErrors.Context.TELEGRAM)

        assertEquals("Telegram 연결이 중단돼 영상을 읽지 못했어요. 연결 상태를 확인하고 다시 시도해 주세요.", message)
        assertFalse(message.orEmpty().contains("SocketTimeoutException"))
    }

    @Test fun recognizedSafeKoreanMessageIsPreserved() {
        val safeMessage = "영상 파일을 읽지 못했어요."

        assertEquals(safeMessage, UserFacingErrors.fromErrorString(safeMessage, UserFacingErrors.Context.LOCAL_MEDIA))
    }

    @Test fun koreanTextMixedWithPrivateAddressTokenOrStackDetailsIsReplaced() {
        val context = UserFacingErrors.Context.LOCAL_MEDIA
        val cases = listOf(
            "영상 확인 실패 https://private.example/video?id=account-secret&token=bot-secret",
            "영상 처리 실패 api_hash=private-hash token=private-token",
            "파일 읽기 실패\njava.lang.IllegalStateException: stack trace at /data/user/0/app/private",
        )
        val fallback = "선택한 영상 정보를 읽지 못했어요. 파일이 온전한지 확인해 주세요."

        cases.forEach { raw ->
            val message = UserFacingErrors.fromErrorString(raw, context)
            assertEquals(fallback, message)
            assertFalse(message.orEmpty().contains("private"))
            assertFalse(message.orEmpty().contains("secret"))
        }
    }

    @Test fun incompleteMediaExceptionKeepsItsSourceSpecificReason() {
        val expectedByContext = mapOf(
            UserFacingErrors.Context.TELEGRAM to "Telegram 영상 데이터를 끝까지 읽지 못했어요. 연결을 확인하고 다시 시도해 주세요.",
            UserFacingErrors.Context.LOCAL_MEDIA to "선택한 영상 파일을 끝까지 읽지 못했어요. 파일이 온전한지 확인한 뒤 다시 시도해 주세요.",
            UserFacingErrors.Context.GENERAL to "미디어 데이터를 끝까지 읽지 못했어요. 입력 파일을 확인한 뒤 다시 시도해 주세요.",
        )

        expectedByContext.forEach { (context, expected) ->
            val fromDiagnostic = UserFacingErrors.fromErrorString("Unterminated array", context)
            val fromTypedFailure = UserFacingErrors.fromThrowable(IncompleteMediaInputException(context))

            assertEquals(expected, fromDiagnostic)
            assertEquals(expected, fromTypedFailure)
            assertTrue(fromTypedFailure.contains("다시 시도") || fromTypedFailure.contains("확인해"))
        }
    }
}
