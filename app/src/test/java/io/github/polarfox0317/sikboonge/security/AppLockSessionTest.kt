package io.github.polarfox0317.sikboonge.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockSessionTest {
    @Test
    fun enablingAndDisablingRequireSuccessfulAuthentication() {
        val disabled = resumedSession(enabled = false)
        val enable = requireNotNull(disabled.beginEnabledChange(true))
        assertEquals(true, disabled.finishAuthentication(enable.generation, succeeded = true))
        assertTrue(disabled.state.enabled)
        assertFalse(disabled.state.locked)

        val disable = requireNotNull(disabled.beginEnabledChange(false))
        assertNull(disabled.finishAuthentication(disable.generation, succeeded = false))
        assertTrue(disabled.state.enabled)
        assertFalse(disabled.state.locked)
    }

    @Test
    fun disablingPersistsOnlyAfterSuccessfulAuthentication() {
        val session = resumedSession(enabled = true)
        val unlock = requireNotNull(session.beginUnlock())
        session.finishAuthentication(unlock.generation, succeeded = true)
        val disable = requireNotNull(session.beginEnabledChange(false))

        assertEquals(false, session.finishAuthentication(disable.generation, succeeded = true))
        assertFalse(session.state.enabled)
        assertFalse(session.state.locked)
    }

    @Test
    fun canceledEnableLeavesPreferenceStateDisabled() {
        val session = resumedSession(enabled = false)
        val request = requireNotNull(session.beginEnabledChange(true))

        assertNull(session.finishAuthentication(request.generation, succeeded = false))
        assertFalse(session.state.enabled)
        assertFalse(session.state.locked)
        assertFalse(session.state.authenticating)
    }

    @Test
    fun enabledColdSessionStartsLocked() {
        val session = AppLockSession(enabled = true, deviceSecure = true)

        assertTrue(session.state.locked)
        assertFalse(session.state.authenticating)
    }

    @Test
    fun realBackgroundRelocksAndRejectsStaleBiometricSuccess() {
        val session = resumedSession(enabled = true)
        val request = requireNotNull(session.beginUnlock())

        session.onHostStopped(configurationChange = false)

        assertNull(session.finishAuthentication(request.generation, succeeded = true))
        assertTrue(session.state.locked)
        assertFalse(session.state.authenticating)
    }

    @Test
    fun configurationChangePreservesGrantedSession() {
        val session = resumedSession(enabled = true)
        val request = requireNotNull(session.beginUnlock())
        session.finishAuthentication(request.generation, succeeded = true)

        session.onHostStopped(configurationChange = true)
        session.syncEnabledFromPreferences(enabled = true)

        assertFalse(session.state.locked)
        assertTrue(session.state.enabled)
    }

    @Test
    fun unchangedEnabledPreferencePreservesActiveAuthentication() {
        val session = resumedSession(enabled = true)
        val request = requireNotNull(session.beginUnlock())

        session.syncEnabledFromPreferences(enabled = true)

        assertEquals(request, session.activeRequest(request.generation))
        assertTrue(session.state.authenticating)
        assertNull(session.finishAuthentication(request.generation, succeeded = true))
        assertFalse(session.state.locked)
    }

    @Test
    fun preferenceChangedByAnotherHostLocksAnAlreadyLoadedDisabledSession() {
        val session = resumedSession(enabled = false)

        session.syncEnabledFromPreferences(enabled = true)

        assertTrue(session.state.enabled)
        assertTrue(session.state.locked)
        assertFalse(session.state.authenticating)
    }

    @Test
    fun explicitCredentialResultCanBeAppliedAfterHostResumes() {
        val session = resumedSession(enabled = true)
        val request = requireNotNull(session.beginUnlock())
        session.onHostStopped(configurationChange = false, expectedCredentialActivity = true)

        assertTrue(session.recordAuthenticationResult(request.generation, succeeded = true))
        val pending = requireNotNull(session.takePendingResultAfterResume())

        assertEquals(request, pending.request)
        session.finishAuthentication(pending.request.generation, pending.succeeded)
        assertFalse(session.state.locked)
    }

    @Test
    fun screenOffInvalidatesCredentialAuthenticationAndItsLateResult() {
        val session = resumedSession(enabled = true)
        val request = requireNotNull(session.beginUnlock())
        session.onHostStopped(configurationChange = false, expectedCredentialActivity = true)

        session.onScreenOff()

        assertFalse(session.recordAuthenticationResult(request.generation, succeeded = true))
        assertNull(session.finishAuthentication(request.generation, succeeded = true))
        assertTrue(session.state.locked)
    }

    @Test
    fun missingDeviceSecurityKeepsEnabledSessionLockedAndUnavailableForUnlock() {
        val session = AppLockSession(enabled = true, deviceSecure = false)

        assertNull(session.beginUnlock())
        assertTrue(session.state.enabled)
        assertTrue(session.state.locked)
    }

    @Test
    fun deferredEnableSuccessSurvivesRepeatedCredentialReturnStopsAndLeaveHints() {
        val session = resumedSession(enabled = false)
        val request = requireNotNull(session.beginEnabledChange(true))
        session.expectCredentialTransition(request.generation)
        session.onHostPaused()

        assertTrue(session.recordAuthenticationResult(request.generation, succeeded = true))
        session.onHostStopped(configurationChange = false)
        assertFalse(session.onUserLeftForBackground())
        session.onHostStopped(configurationChange = false)

        assertEquals(request, session.activeRequest(request.generation))
        assertTrue(session.hasPendingAuthenticationResult(request.generation))
        assertFalse(session.state.enabled)
        assertNull(session.beginEnabledChange(true))
        val pending = requireNotNull(session.takePendingResultAfterResume())
        assertEquals(true, session.finishAuthentication(pending.request.generation, pending.succeeded))
        assertTrue(session.state.enabled)
        assertFalse(session.state.authenticating)
        assertFalse(session.isExpectedCredentialTransition(request.generation))
    }

    @Test
    fun canceledPendingCredentialResultLeavesSettingDisabledAfterReturn() {
        val session = resumedSession(enabled = false)
        val request = requireNotNull(session.beginEnabledChange(true))
        session.expectCredentialTransition(request.generation)
        session.onHostPaused()
        assertTrue(session.recordAuthenticationResult(request.generation, succeeded = false))

        session.onHostStopped(configurationChange = false)
        assertFalse(session.onUserLeftForBackground())
        session.onHostStopped(configurationChange = false)
        val pending = requireNotNull(session.takePendingResultAfterResume())

        assertNull(session.finishAuthentication(pending.request.generation, pending.succeeded))
        assertFalse(session.state.enabled)
        assertFalse(session.state.authenticating)
    }

    @Test
    fun explicitHomeInvalidatesEvenSuccessfulPendingCredentialResult() {
        val session = resumedSession(enabled = false)
        val request = requireNotNull(session.beginEnabledChange(true))
        session.expectCredentialTransition(request.generation)
        session.onHostPaused()
        assertTrue(session.recordAuthenticationResult(request.generation, succeeded = true))

        assertTrue(session.onUserLeftForBackground(force = true))

        assertNull(session.takePendingResultAfterResume())
        assertNull(session.activeRequest(request.generation))
        assertNull(session.finishAuthentication(request.generation, succeeded = true))
        assertFalse(session.state.enabled)
    }

    @Test
    fun recentAppsLeaveCancelsSpeculativeCredentialHandoffAndAllowsRetry() {
        val session = resumedSession(enabled = true)
        val abandoned = requireNotNull(session.beginUnlock())
        session.expectCredentialTransition(abandoned.generation)

        assertTrue(session.onUserLeaveHint(separateCredentialActivityStarted = false))
        assertNull(session.activeRequest(abandoned.generation))
        assertTrue(session.state.locked)
        assertFalse(session.state.authenticating)

        session.takePendingResultAfterResume()
        val retry = requireNotNull(session.beginUnlock())
        assertTrue(retry.generation > abandoned.generation)
        assertEquals(null, session.finishAuthentication(retry.generation, succeeded = false))
        assertTrue(session.state.locked)
        assertFalse(session.state.authenticating)

        val finalRetry = requireNotNull(session.beginUnlock())
        session.finishAuthentication(finalRetry.generation, succeeded = true)
        assertFalse(session.state.locked)
    }

    @Test
    fun separateCredentialActivityLeavePreservesItsPendingResult() {
        val session = resumedSession(enabled = true)
        val request = requireNotNull(session.beginUnlock())
        session.expectCredentialTransition(request.generation)

        assertFalse(session.onUserLeaveHint(separateCredentialActivityStarted = true))
        assertEquals(request, session.activeRequest(request.generation))
        assertTrue(session.state.authenticating)
    }

    @Test
    fun completedPendingCredentialResultRetainsGenerationAcrossConfigurationChange() {
        val session = resumedSession(enabled = false)
        val request = requireNotNull(session.beginEnabledChange(true))
        session.expectCredentialTransition(request.generation)
        session.onHostPaused()
        assertTrue(session.recordAuthenticationResult(request.generation, succeeded = true))

        session.onHostStopped(configurationChange = true)

        assertTrue(session.hasPendingAuthenticationResult(request.generation))
        assertTrue(session.isExpectedCredentialTransition(request.generation))
        val pending = requireNotNull(session.takePendingResultAfterResume())
        assertEquals(true, session.finishAuthentication(pending.request.generation, pending.succeeded))
        assertTrue(session.state.enabled)
    }

    @Test
    fun credentialSuccessArrivingAfterResumeCompletesWithoutRemainingAuthenticating() {
        val session = resumedSession(enabled = false)
        val request = requireNotNull(session.beginEnabledChange(true))
        session.expectCredentialTransition(request.generation)
        session.onHostPaused()
        session.onHostStopped(configurationChange = false)

        assertNull(session.takePendingResultAfterResume())
        assertFalse(session.recordAuthenticationResult(request.generation, succeeded = true))

        assertEquals(true, session.finishAuthentication(request.generation, succeeded = true))
        assertTrue(session.state.enabled)
        assertFalse(session.state.authenticating)
        assertFalse(session.hasPendingAuthenticationResult(request.generation))
    }

    private fun resumedSession(enabled: Boolean): AppLockSession =
        AppLockSession(enabled = enabled, deviceSecure = true).also {
            it.takePendingResultAfterResume()
        }
}
/**
 * A Recents return resumes the host before its window owns focus; the automatic prompt may only
 * start once focus is actually held, and only once.
 */
class AppLockAutoPromptGateTest {
    private fun gate(
        hostResumed: Boolean = true,
        windowFocused: Boolean = false,
        alreadyAttempted: Boolean = false,
        authenticating: Boolean = false,
    ) = AppLockAutoPromptGate.shouldStart(
        enabled = true,
        locked = true,
        deviceSecure = true,
        authenticating = authenticating,
        hostResumed = hostResumed,
        windowFocused = windowFocused,
        alreadyAttempted = alreadyAttempted,
    )

    // The reported defect: resumed, but no window focus yet -> no prompt.
    @Test fun doesNotStartWhenResumedWithoutWindowFocus() {
        assertFalse(gate(hostResumed = true, windowFocused = false))
    }

    @Test fun startsWhenFocusLaterArrives() {
        assertTrue(gate(hostResumed = true, windowFocused = true))
    }

    // Each focus arrival is one chance; a repeated notification must not reopen the prompt.
    @Test fun startsOnlyOnce() {
        assertTrue(gate(windowFocused = true, alreadyAttempted = false))
        assertFalse(gate(windowFocused = true, alreadyAttempted = true))
    }

    // The ordinary cold start resumes with focus already held and still prompts.
    @Test fun coldStartWithFocusPromptImmediately() {
        assertTrue(gate(hostResumed = true, windowFocused = true, alreadyAttempted = false))
    }

    // Focus alone is never enough: the host must be resumed for the lifecycle.
    @Test fun needsResumedHostAsWellAsFocus() {
        assertFalse(gate(hostResumed = false, windowFocused = true))
    }

    // A prompt already running (or a button press in flight) is left alone, and the gate keeps
    // the explicit-button path untouched because only this helper consults it.
    @Test fun inFlightAuthenticationIsLeftAlone() {
        assertFalse(gate(windowFocused = true, authenticating = true))
    }
}
/**
 * The automatic prompt is one attempt per resume. The system prompt takes window focus while it
 * shows, so a blur/refocus pair inside one resume must not auto-trigger a second prompt.
 */
class AppLockAutoPromptLifecycleTest {
    // Reports whether the controller may start the automatic prompt for the given focus history.
    private class Attempts {
        var windowFocused = false
        private var attempted = false

        fun resume() {
            attempted = false
        }

        fun focus(hasFocus: Boolean): Boolean {
            windowFocused = hasFocus
            if (!hasFocus) return false
            val start = AppLockAutoPromptGate.shouldStart(
                enabled = true, locked = true, deviceSecure = true, authenticating = false,
                hostResumed = true, windowFocused = windowFocused, alreadyAttempted = attempted,
            )
            if (start) attempted = true
            return start
        }
    }

    // The reported case: a resume takes a new attempt, and the first focus after it starts one.
    @Test fun resumeWithoutFocusThenFirstFocusStartsOnePrompt() {
        val attempts = Attempts()
        attempts.resume()
        assertTrue(attempts.focus(true))
        assertFalse("a second focus in the same resume restarted it", attempts.focus(true))
    }

    // The biometric window takes focus, then returns it: no second automatic prompt.
    @Test fun blurAndRefocusWithinOneResumeDoesNotRestart() {
        val attempts = Attempts()
        attempts.resume()
        assertTrue(attempts.focus(true))
        attempts.focus(false)
        assertFalse("refocus after the prompt closed restarted it", attempts.focus(true))
        // Even repeated prompt round trips inside one resume keep a single automatic attempt.
        attempts.focus(false)
        assertFalse(attempts.focus(true))
    }

    // A later resume is a new attempt, which the user may need after cancelling.
    @Test fun nextResumeAllowsOneMoreAttempt() {
        val attempts = Attempts()
        attempts.resume()
        assertTrue(attempts.focus(true))
        assertFalse(attempts.focus(true))
        attempts.resume()
        assertTrue(attempts.focus(true))
    }

    // The explicit button path needs no window focus and is never gated.
    @Test fun explicitUnlockUnaffectedByGate() {
        val session = AppLockSession(enabled = true, deviceSecure = true).also {
            it.takePendingResultAfterResume()
        }
        val request = requireNotNull(session.beginUnlock())
        assertEquals(AppLockAuthenticationPurpose.Unlock, request.purpose)
        assertTrue(session.state.authenticating)
        // The automatic gate refuses this same state while unfocused, so the two paths differ.
        assertFalse(AppLockAutoPromptGate.shouldStart(
            enabled = true, locked = true, deviceSecure = true, authenticating = false,
            hostResumed = true, windowFocused = false, alreadyAttempted = false,
        ))
    }
}
/**
 * A stale home/recent broadcast after a Recents return must not invalidate the resumed session;
 * a signal seen while the Activity is genuinely backgrounded still must.
 */
class AppLockBackgroundSignalPolicyTest {
    // The verified race: broadcast lands after onResume -> ignore it, unlock stays available.
    @Test fun staleSignalWhileResumedIsIgnored() {
        assertFalse(AppLockBackgroundSignalPolicy.shouldInvalidateFromSystemDialogSignal(lifecycleResumed = true))
    }

    @Test fun signalWhileBackgroundedInvalidates() {
        assertTrue(AppLockBackgroundSignalPolicy.shouldInvalidateFromSystemDialogSignal(lifecycleResumed = false))
    }

    // A genuine leave still invalidates through the controller's own background path.
    @Test fun genuineUserLeaveStillInvalidates() {
        val session = AppLockSession(enabled = true, deviceSecure = true).also {
            it.takePendingResultAfterResume()
        }
        assertTrue(session.onUserLeftForBackground(force = true))
        assertTrue(session.state.locked)
        assertFalse(session.state.authenticating)
    }
}