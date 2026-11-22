package io.github.polarfox0317.sikboonge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contract for the request a user resume sends after the app stopped serving playback: it must
 * name the media, metadata and start position, because a fresh process has no service state.
 * Offline: builds and inspects intents only, never starts a service or plays audio.
 */
@RunWith(AndroidJUnit4::class)
class RestoredPlaybackRequestTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun botResumeNamesFileIdPositionAndMetadata() {
        val intent = StreamingAudioService.resumeIntent(context, StreamingAudioService.ResumeSpec(
            positionMs = 42_000L,
            title = "봇 영상 2",
            artworkPath = "/thumb/2.jpg",
            artworkBase64 = "QVJUV09SSw==",
            botFileId = 20,
            botBytes = 1_000L,
        ))
        // A cold process re-opens the bot item through the same action that normally starts it.
        assertEquals(BotRuntime.ACTION_PLAY_TDLIB, intent.action)
        assertEquals(20, intent.getIntExtra(BotRuntime.EXTRA_TDLIB_FILE_ID, 0))
        assertEquals("봇 영상 2", intent.getStringExtra(BotRuntime.EXTRA_TDLIB_NAME))
        assertEquals(1_000L, intent.getLongExtra(BotRuntime.EXTRA_TDLIB_SIZE, -1L))
        assertEquals(42_000L, intent.getLongExtra(StreamingAudioService.EXTRA_POSITION, -1L))
        assertEquals("/thumb/2.jpg", intent.getStringExtra(StreamingAudioService.EXTRA_ARTWORK_PATH))
        assertEquals("QVJUV09SSw==", intent.getStringExtra(StreamingAudioService.EXTRA_ARTWORK_BASE64))
    }

    @Test fun localResumeNamesPathMimeTitleAndArtwork() {
        val intent = StreamingAudioService.resumeIntent(context, StreamingAudioService.ResumeSpec(
            positionMs = 5_000L,
            localAudioPath = "/data/user/0/pkg/files/a.m4a",
            mime = "audio/mp4",
            title = "보관함 a",
            artworkPath = "/art/a.jpg",
        ))
        assertEquals(StreamingAudioService.ACTION_PLAY_LOCAL, intent.action)
        assertEquals("/data/user/0/pkg/files/a.m4a", intent.getStringExtra(StreamingAudioService.EXTRA_LOCAL_AUDIO_PATH))
        assertEquals("audio/mp4", intent.getStringExtra(StreamingAudioService.EXTRA_LOCAL_AUDIO_MIME))
        assertEquals("보관함 a", intent.getStringExtra(StreamingAudioService.EXTRA_LOCAL_AUDIO_TITLE))
        assertEquals("/art/a.jpg", intent.getStringExtra(StreamingAudioService.EXTRA_ARTWORK_PATH))
        assertEquals(5_000L, intent.getLongExtra(StreamingAudioService.EXTRA_POSITION, -1L))
    }

    // Without resolved media the request must not pretend to carry one.
    @Test fun unresolvableResumeCarriesNoMedia() {
        val intent = StreamingAudioService.resumeIntent(context, StreamingAudioService.ResumeSpec(positionMs = 1_000L))
        assertEquals(StreamingAudioService.ACTION_RESUME, intent.action)
        assertNull(intent.getStringExtra(StreamingAudioService.EXTRA_LOCAL_AUDIO_PATH))
        assertEquals(0, intent.getIntExtra(BotRuntime.EXTRA_TDLIB_FILE_ID, 0))
    }

    // Nothing here starts a service or publishes playback; the request is inert until sent.
    @Test fun buildingARequestDoesNotPublishPlayback() {
        val before = StreamPlayback.state.value
        StreamingAudioService.resumeIntent(context, StreamingAudioService.ResumeSpec(positionMs = 9L, botFileId = 20))
        assertEquals(before, StreamPlayback.state.value)
    }
}
