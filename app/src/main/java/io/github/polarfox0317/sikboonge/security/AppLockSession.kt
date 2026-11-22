package io.github.polarfox0317.sikboonge.security

/**
 * Whether the automatic unlock prompt may start now. The system's biometric window needs both a
 * resumed host and an actually focused Activity window: after a Recents return the host resumes
 * before its window owns focus, and a prompt started in that gap attaches and detaches at once.
 * The prompt is attempted only once per resume, so a focus change never re-triggers it.
 */
internal object AppLockAutoPromptGate {
    fun shouldStart(
        enabled: Boolean,
        locked: Boolean,
        deviceSecure: Boolean,
        authenticating: Boolean,
        hostResumed: Boolean,
        windowFocused: Boolean,
        alreadyAttempted: Boolean,
    ): Boolean = enabled && locked && deviceSecure && !authenticating &&
        hostResumed && windowFocused && !alreadyAttempted
}

/**
 * The system's `CLOSE_SYSTEM_DIALOGS` home/recent signal can arrive after this Activity has already
 * resumed (Recents return). Acting on it then clears the resumed session and makes the unlock
 * button report "host unavailable", while the real leave is already observed by
 * `onUserLeaveHint`/`onPause`/`onStop`. Only a signal for an Activity that is no longer resumed
 * invalidates the session.
 */
internal object AppLockBackgroundSignalPolicy {
    /** A resumed Activity already proves the user came back; a stale signal must be ignored. */
    fun shouldInvalidateFromSystemDialogSignal(lifecycleResumed: Boolean): Boolean = !lifecycleResumed
}

/** State exposed to the app shell. Runtime unlock state is deliberately kept only in memory. */
data class AppLockUiState(
    val enabled: Boolean = false,
    val locked: Boolean = false,
    val authenticating: Boolean = false,
    val deviceSecure: Boolean = false,
    val message: String? = null,
    /**
     * Identifies one lock-feedback event, so repeating the same failure text still announces again.
     * Pure metadata: it carries no lock, authentication or lifecycle meaning.
     */
    val messageId: Long = 0L,
)

internal enum class AppLockAuthenticationPurpose { Unlock, Enable, Disable }

internal data class AppLockAuthenticationRequest(
    val generation: Long,
    val purpose: AppLockAuthenticationPurpose,
    val desiredEnabled: Boolean?,
    val wasLocked: Boolean,
)

internal data class AppLockAuthenticationResult(
    val request: AppLockAuthenticationRequest,
    val succeeded: Boolean,
)

/** Pure in-memory lock policy. Android callbacks and preference writes are owned by the controller. */
internal class AppLockSession(
    enabled: Boolean,
    deviceSecure: Boolean,
) {
    var state: AppLockUiState = AppLockUiState(
        enabled = enabled,
        locked = enabled,
        deviceSecure = deviceSecure,
    )
        private set

    private var nextGeneration = 0L
    private var activeRequest: AppLockAuthenticationRequest? = null
    private var pendingResult: AppLockAuthenticationResult? = null
    private var expectedCredentialTransitionGeneration: Long? = null
    private var hostResumed = false
    private var stoppedDuringAuthentication = false

    fun beginUnlock(): AppLockAuthenticationRequest? {
        if (!state.enabled || !state.locked) return null
        return begin(AppLockAuthenticationPurpose.Unlock, desiredEnabled = null)
    }

    fun beginEnabledChange(enabled: Boolean): AppLockAuthenticationRequest? {
        if (enabled == state.enabled) return null
        return begin(
            purpose = if (enabled) AppLockAuthenticationPurpose.Enable else AppLockAuthenticationPurpose.Disable,
            desiredEnabled = enabled,
        )
    }

    private fun begin(
        purpose: AppLockAuthenticationPurpose,
        desiredEnabled: Boolean?,
    ): AppLockAuthenticationRequest? {
        if (!state.deviceSecure || state.authenticating || activeRequest != null || pendingResult != null) return null
        val request = AppLockAuthenticationRequest(
            generation = ++nextGeneration,
            purpose = purpose,
            desiredEnabled = desiredEnabled,
            wasLocked = state.locked,
        )
        activeRequest = request
        stoppedDuringAuthentication = false
        state = state.copy(authenticating = true, message = null)
        return request
    }

    fun activeRequest(generation: Long): AppLockAuthenticationRequest? =
        activeRequest?.takeIf { it.generation == generation }

    fun expectCredentialTransition(generation: Long) {
        if (activeRequest(generation) != null) expectedCredentialTransitionGeneration = generation
    }

    fun isExpectedCredentialTransition(generation: Long?): Boolean =
        generation != null && activeRequest(generation) != null &&
            expectedCredentialTransitionGeneration == generation

    fun hasPendingAuthenticationResult(generation: Long?): Boolean =
        generation != null && pendingResult?.request?.generation == generation

    fun onHostPaused() {
        hostResumed = false
    }

    /** Explicit Home/screen-off invalidation wins over an expected credential return. */
    fun onUserLeftForBackground(force: Boolean = false): Boolean {
        if (!force && isExpectedCredentialTransition(activeRequest?.generation)) return false
        hostResumed = false
        invalidateAuthentication(lockIfEnabled = true)
        return true
    }

    /** A user-selected task switch cancels speculative prompt handoff, but keeps an actual
     * device-credential Activity result alive while that separate system Activity is showing. */
    fun onUserLeaveHint(separateCredentialActivityStarted: Boolean): Boolean =
        onUserLeftForBackground(force = !separateCredentialActivityStarted)

    /** Records platform results, but does not apply them until the host is resumed. */
    fun recordAuthenticationResult(generation: Long, succeeded: Boolean): Boolean {
        val request = activeRequest(generation) ?: return false
        if (!hostResumed) {
            pendingResult = AppLockAuthenticationResult(request, succeeded)
            return true
        }
        return false
    }

    fun takePendingResultAfterResume(): AppLockAuthenticationResult? {
        hostResumed = true
        return pendingResult.also { pendingResult = null }
    }

    /** Applies a current result. A null persistence value means this was an unlock operation. */
    fun finishAuthentication(
        generation: Long,
        succeeded: Boolean,
        persistenceSucceeded: Boolean = true,
    ): Boolean? {
        val request = activeRequest(generation) ?: return null
        activeRequest = null
        pendingResult = null
        expectedCredentialTransitionGeneration = null
        val accepted = succeeded && state.deviceSecure && persistenceSucceeded
        if (accepted) {
            request.desiredEnabled?.let { enabled ->
                state = state.copy(enabled = enabled, locked = false, authenticating = false, message = null)
            } ?: run {
                state = state.copy(locked = false, authenticating = false, message = null)
            }
            stoppedDuringAuthentication = false
            return request.desiredEnabled
        }

        val shouldRemainLocked = when {
            request.purpose == AppLockAuthenticationPurpose.Unlock -> true
            state.enabled && stoppedDuringAuthentication -> true
            else -> request.wasLocked
        }
        state = state.copy(locked = shouldRemainLocked, authenticating = false)
        stoppedDuringAuthentication = false
        return null
    }

    fun updateDeviceSecure(deviceSecure: Boolean) {
        state = state.copy(
            deviceSecure = deviceSecure,
            locked = if (state.enabled && !deviceSecure) true else state.locked,
        )
    }

    /** Refreshes a persisted setting changed by another Activity instance. */
    fun syncEnabledFromPreferences(enabled: Boolean) {
        if (enabled == state.enabled) return
        activeRequest = null
        pendingResult = null
        expectedCredentialTransitionGeneration = null
        stoppedDuringAuthentication = false
        state = state.copy(
            enabled = enabled,
            locked = enabled,
            authenticating = false,
            message = null,
        )
    }

    fun onHostStopped(configurationChange: Boolean, expectedCredentialActivity: Boolean = false) {
        hostResumed = false
        if (configurationChange) return
        val expectedTransition = expectedCredentialActivity ||
            isExpectedCredentialTransition(activeRequest?.generation)
        if (state.authenticating && !expectedTransition) {
            invalidateAuthentication(lockIfEnabled = true)
            return
        }
        if (state.authenticating) stoppedDuringAuthentication = true
        if (state.enabled) state = state.copy(locked = true)
    }

    fun onScreenOff() {
        hostResumed = false
        invalidateAuthentication(lockIfEnabled = true)
    }

    fun setMessage(message: String?, id: Long = state.messageId) {
        state = state.copy(message = message, messageId = id)
    }

    fun invalidateAuthentication(lockIfEnabled: Boolean = true) {
        activeRequest = null
        pendingResult = null
        expectedCredentialTransitionGeneration = null
        stoppedDuringAuthentication = false
        state = state.copy(
            authenticating = false,
            locked = if (lockIfEnabled && state.enabled) true else state.locked,
        )
    }
}
