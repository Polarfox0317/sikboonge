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
