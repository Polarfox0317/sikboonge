package io.github.polarfox0317.sikboonge.security

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Drives the production controller with live host facts and deliberately missing/late callbacks. */
class AppLockControllerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val settings = object : AppLockSettingsStore {
        var enabled = true
        var writes = 0
        override fun isEnabled() = enabled
        override fun setEnabled(enabled: Boolean): Boolean {
            writes++
            this.enabled = enabled
            return true
        }
    }
    private val controller = AppLockController(settings, scope, logEvent = {})
    private var owner = controller.attachHost()
    private var host = FakeHost().also { controller.attachCoordinator(owner, it) }

    private inner class FakeHost : AppLockSystemAuthCoordinator {
        var resumed = false
        var focused = false
        var secure = true
        var throwsOnStart = false
        var cancelCount = 0
        val requests = mutableListOf<AppLockAuthenticationRequest>()
        val reattached = mutableListOf<Long>()
        override fun isHostResumed() = resumed
        override fun hasWindowFocus() = focused
        override fun isDeviceSecure() = secure
        override fun authenticate(request: AppLockAuthenticationRequest) {
            requests += request
            if (throwsOnStart) throw IllegalStateException("fake host rejected start")
            controller.onCredentialTransferPossible(owner, request.generation)
        }
        override fun reattachCredentialActivity(generation: Long) { reattached += generation }
        override fun cancelAuthentication() { cancelCount++ }
        override fun openSecuritySettings() = true
    }

    @After fun dispose() { scope.cancel() }

    private fun resume(focus: Boolean = false) {
        host.resumed = true
        host.focused = focus
        controller.onHostStarted()
        controller.onHostResumed()
    }
    private fun focus() {
        host.focused = true
        controller.onWindowFocusChanged(true)
    }
    private fun leave(stop: Boolean = true) {
        host.resumed = false
        host.focused = false
        controller.onHostPaused()
        if (stop) controller.onHostStopped(configurationChange = false)
    }
    private fun result(request: AppLockAuthenticationRequest, success: Boolean) =
        controller.onAuthenticationResult(owner, request.generation, success)

    @Test fun coldLaunchWaitsForFocusAndRequiresSystemSuccess() {
        resume()
        assertTrue(host.requests.isEmpty())
        focus()
        assertEquals(1, host.requests.size)
        assertTrue(controller.state.value.locked)
        result(host.requests.single(), true)
        assertFalse(controller.state.value.locked)
        assertEquals(0, settings.writes)
    }

    @Test fun screenOffWithoutLifecyclePauseCannotPoisonTheUnlockButton() {
        resume(focus = true)
        val old = host.requests.single()
        controller.onScreenOff()
        assertTrue(controller.state.value.locked)
        controller.unlock()
        val next = host.requests.last()
        assertTrue(next.generation > old.generation)
        result(old, true)
        assertTrue(controller.state.value.locked)
        result(next, true)
        assertFalse(controller.state.value.locked)
    }

    @Test fun forcedBackgroundSignalDoesNotChangeActualHostReadiness() {
        resume(focus = true)
        controller.onUserLeftForBackground(force = true)
        controller.unlock()
        assertEquals(2, host.requests.size)
        assertNull(controller.state.value.message)
        assertTrue(controller.state.value.locked)
    }

    @Test fun stoppedPromptWithoutCallbackIsRetiredOnReturn() {
        resume(focus = true)
        val old = host.requests.single()
        leave()
        assertTrue(controller.state.value.authenticating)
        resume()
        assertFalse(controller.state.value.authenticating)
        assertTrue(controller.state.value.locked)
        controller.unlock() // explicit request waits for focus; does not fail host-unavailable
        assertEquals(1, host.requests.size)
        focus()
        assertEquals(2, host.requests.size)
        result(old, true)
        assertTrue(controller.state.value.locked)
        result(host.requests.last(), true)
        assertFalse(controller.state.value.locked)
    }

    @Test fun pausedPromptWithoutStopAlsoRecovers() {
        resume(focus = true)
        leave(stop = false)
        resume(focus = true)
        assertEquals(1, host.requests.size)
        assertFalse(controller.state.value.authenticating)
        controller.unlock()
        assertEquals(2, host.requests.size)
        assertTrue(controller.state.value.authenticating)
    }

    @Test fun pinSuccessAfterResumeAndFocusIsStillAcceptedWithoutAnotherPrompt() {
        resume(focus = true)
        val request = host.requests.single()
        leave()
        resume(focus = true)
        focus()
        assertEquals(1, host.requests.size)
        assertTrue(controller.state.value.locked)
        result(request, true)
        assertFalse(controller.state.value.locked)
        assertEquals(1, host.requests.size)
    }

    @Test fun focusCanDrainAResultIfActivityResumeCallbackPrecededTheLifecycleEvent() {
        resume(focus = true)
        val request = host.requests.single()
        leave()
        result(request, true)
        controller.onHostResumed() // still STARTED; must not apply the result yet
        assertTrue(controller.state.value.locked)
        host.resumed = true
        focus()
        assertFalse(controller.state.value.locked)
        assertEquals(1, host.requests.size)
    }

    @Test fun cancellationAndRecentsReturnCanRepeatThreeTimes() {
        resume(focus = true)
        val generations = mutableSetOf<Long>()
        repeat(3) {
            val request = host.requests.last()
            assertTrue(generations.add(request.generation))
            result(request, false)
            assertTrue(controller.state.value.locked)
            leave()
            resume(focus = true)
        }
        assertEquals(4, host.requests.size)
    }

    @Test fun cancellationCanBeRetriedWithoutLeaving() {
        resume(focus = true)
        result(host.requests.single(), false)
        controller.unlock()
        assertEquals(2, host.requests.size)
        result(host.requests.last(), true)
        assertFalse(controller.state.value.locked)
    }

    @Test fun pendingButtonIntentStartsOnlyOnceAfterFocusReturns() {
        resume()
        repeat(3) { controller.unlock() }
        assertTrue(host.requests.isEmpty())
        focus()
        controller.onWindowFocusChanged(false)
        focus()
        assertEquals(1, host.requests.size)
    }

    @Test fun leavingDiscardsPendingButtonIntent() {
        resume()
        controller.unlock()
        leave()
        resume(focus = true)
        assertEquals("only the new resume's automatic attempt", 1, host.requests.size)
        result(host.requests.single(), false)
        focus()
        assertEquals(1, host.requests.size)
    }

    @Test fun successfulResultReceivedWhileAwayIsAppliedOnActualResumeOnly() {
        resume(focus = true)
        val request = host.requests.single()
        leave()
        result(request, true)
        assertTrue(controller.state.value.locked)
        resume()
        assertFalse(controller.state.value.locked)
        assertEquals(1, host.requests.size)
    }

    @Test fun separateCredentialResultSurvivesHostRecreation() {
        resume(focus = true)
        val request = host.requests.single()
        controller.onCredentialActivityStarted(owner, request.generation)
        leave()
        assertTrue(controller.onHostDetached(owner, configurationChange = true))
        val oldOwner = owner
        owner = controller.attachHost()
        host = FakeHost().also { controller.attachCoordinator(owner, it) }
        assertEquals(listOf(request.generation), host.reattached)
        controller.onAuthenticationResult(oldOwner, request.generation, true)
        assertTrue(controller.state.value.locked)
        result(request, true)
        resume()
        assertFalse(controller.state.value.locked)
        assertTrue(host.requests.isEmpty())
    }

    @Test fun homeWhileSeparateCredentialIsOpenInvalidatesLateSuccess() {
        resume(focus = true)
        val request = host.requests.single()
        controller.onCredentialActivityStarted(owner, request.generation)
        leave()
        controller.onUserLeftForBackground(force = true)
        result(request, true)
        assertTrue(controller.state.value.locked)
        resume(focus = true)
        assertEquals(2, host.requests.size)
    }

    @Test fun thrownStartLeavesRetryableLockedState() {
        resume()
        host.throwsOnStart = true
        focus()
        assertFalse(controller.state.value.authenticating)
        assertTrue(controller.state.value.locked)
        assertNotNull(controller.state.value.message)
        host.throwsOnStart = false
        controller.unlock()
        result(host.requests.last(), true)
        assertFalse(controller.state.value.locked)
    }

    @Test fun disablingLockRequiresSuccessfulAuthenticationBeforeSaving() {
        resume(focus = true)
        result(host.requests.single(), true)
        controller.requestEnabled(false)
        assertEquals(0, settings.writes)
        result(host.requests.last(), false)
        assertTrue(settings.enabled)
        controller.requestEnabled(false)
        result(host.requests.last(), true)
        assertEquals(1, settings.writes)
        assertFalse(settings.enabled)
    }

    @Test fun removingDeviceSecurityNeverUnlocksTheApp() {
        resume(focus = true)
        host.secure = false
        result(host.requests.single(), true)
        assertTrue(controller.state.value.locked)
        assertFalse(controller.state.value.authenticating)
        assertTrue(settings.enabled)
    }
}
