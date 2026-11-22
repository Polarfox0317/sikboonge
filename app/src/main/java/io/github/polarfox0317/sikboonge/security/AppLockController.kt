package io.github.polarfox0317.sikboonge.security

import android.content.Context
import android.util.Log
import io.github.polarfox0317.sikboonge.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppLockController internal constructor(
    private val preferences: AppLockSettingsStore,
    private val messageScope: CoroutineScope? = null,
    private val logEvent: (String) -> Unit = { text ->
        if (BuildConfig.DEBUG) Log.d("AppLock", text)
    },
) : ViewModel() {
    internal constructor(context: Context) : this(AppLockPreferences(context.applicationContext))
    private val session = AppLockSession(
        enabled = preferences.isEnabled(),
        deviceSecure = false,
    )
    private val mutableState = MutableStateFlow(session.state)
    val state: StateFlow<AppLockUiState> = mutableState.asStateFlow()

    private var hostGeneration = 0L
    private var coordinator: AppLockSystemAuthCoordinator? = null
    private val hostResumed: Boolean get() = coordinator?.isHostResumed() == true
    private val windowFocused: Boolean get() = coordinator?.hasWindowFocus() == true
    private enum class ExplicitIntent { Unlock, Enable, Disable }
    private var pendingExplicitIntent: ExplicitIntent? = null
    private var autoPromptAttempted = false
    private var stoppedDuringAuthentication = false
    // A returning combined prompt may still deliver a valid PIN result. Keep its generation
    // alive, but let an explicit button press replace it if the platform never calls back.
    private var retryableRequestGeneration: Long? = null
    private var credentialActivityGeneration: Long? = null
    private var credentialTransferPossibleGeneration: Long? = null
    private var messageGeneration = 0L
    private var messageJob: Job? = null

    fun requestEnabled(enabled: Boolean) {
        debugEvent(if (enabled) "request_enable" else "request_disable")
        if (enabled == session.state.enabled) {
            debugEvent("request_unchanged")
            return
        }
        session.updateDeviceSecure(coordinator?.isDeviceSecure() == true)
        if (!session.state.deviceSecure) {
            debugEvent("request_device_insecure")
            showMessage("기기 화면 잠금을 설정한 뒤 사용할 수 있어요.")
            return
        }
        requestExplicitAuthentication(if (enabled) ExplicitIntent.Enable else ExplicitIntent.Disable)
    }

    fun unlock() {
        debugEvent("unlock")
        session.updateDeviceSecure(coordinator?.isDeviceSecure() == true)
        if (!session.state.deviceSecure) {
            showMessage("기기 화면 잠금을 설정한 뒤 잠금을 해제할 수 있어요.")
            return
        }
        requestExplicitAuthentication(ExplicitIntent.Unlock)
    }

    private fun requestExplicitAuthentication(intent: ExplicitIntent) {
        if (session.state.authenticating && retryableRequestGeneration != currentRequest?.generation) return
        // Keep one user intent until the real Activity can host the prompt. Do not create an
        // authentication generation in a transient pause/focus gap and immediately fail it.
        pendingExplicitIntent = intent
        startPendingExplicitAuthenticationIfReady()
    }

    private fun startPendingExplicitAuthenticationIfReady(): Boolean {
        val intent = pendingExplicitIntent ?: return false
        if (!hostResumed || !windowFocused) return false
        drainPendingAuthenticationResultIfResumed()
        val abandoned = currentRequest?.takeIf { it.generation == retryableRequestGeneration }
        if (abandoned != null) {
            coordinator?.cancelAuthentication()
            session.finishAuthentication(abandoned.generation, succeeded = false)
            currentRequest = null
            credentialTransferPossibleGeneration = null
            retryableRequestGeneration = null
            debugEvent("abandoned_prompt_replaced", abandoned.generation)
        }
        if (session.state.authenticating) return false
        pendingExplicitIntent = null
        session.updateDeviceSecure(coordinator?.isDeviceSecure() == true)
        drainPendingAuthenticationResultIfResumed()
        val request = when (intent) {
            ExplicitIntent.Unlock -> session.beginUnlock()
            ExplicitIntent.Enable -> session.beginEnabledChange(true)
            ExplicitIntent.Disable -> session.beginEnabledChange(false)
        }
        startAuthentication(request)
        return true
    }

    fun openSecuritySettings() {
        if (coordinator?.openSecuritySettings() != true) {
            showMessage("기기 보안 설정을 열지 못했어요.")
        }
    }

    internal fun attachHost(): Long = ++hostGeneration

    internal fun attachCoordinator(owner: Long, newCoordinator: AppLockSystemAuthCoordinator) {
        if (owner != hostGeneration) return
        coordinator = newCoordinator
        val request = currentRequest?.let { session.activeRequest(it.generation) }
        if (request != null) {
            if (credentialActivityGeneration == request.generation &&
                !session.hasPendingAuthenticationResult(request.generation)
            ) {
                newCoordinator.reattachCredentialActivity(request.generation)
            }
        }
    }

    private var currentRequest: AppLockAuthenticationRequest? = null

    internal fun onHostStarted() {
        debugEvent("start")
        syncPersistedSetting()
    }

    internal fun onHostResumed() {
        debugEvent("resume")
        syncPersistedSetting()
        session.updateDeviceSecure(coordinator?.isDeviceSecure() == true)
        // Do not cancel a returning PIN prompt before its main-executor success callback arrives.
        // If no callback ever arrives, expose the unlock button so the user can replace the
        // orphaned generation explicitly. Automatic prompting must not replace this request.
        val request = currentRequest
        if (stoppedDuringAuthentication && request != null &&
            credentialActivityGeneration != request.generation &&
            !session.hasPendingAuthenticationResult(request.generation)
        ) {
            retryableRequestGeneration = request.generation
            debugEvent("prompt_retry_available", request.generation)
        }
        stoppedDuringAuthentication = false
        drainPendingAuthenticationResultIfResumed()
        publish()

        debugEvent("resume_complete")

        // Focus may have arrived before this resume; a fresh attempt belongs to this resume.
        autoPromptAttempted = false
        if (!startPendingExplicitAuthenticationIfReady()) startAutomaticUnlockIfReady()
    }

    /**
     * The Activity window took or lost focus. After a Recents return the host is already resumed
     * here, so this is where the automatic prompt becomes safe to start. Focus loss does not
     * re-arm the attempt: the system prompt itself takes focus while showing, so re-arming on
     * blur would start a second prompt when that prompt closes.
     */
    internal fun onWindowFocusChanged(hasFocus: Boolean) {
        debugEvent("window_focus")
        if (!hasFocus) return
        drainPendingAuthenticationResultIfResumed()
        publish()
        if (!startPendingExplicitAuthenticationIfReady()) startAutomaticUnlockIfReady()
    }

    /** Starts the automatic unlock prompt only when the gate allows it; never on a button press. */
    private fun startAutomaticUnlockIfReady() {
        if (currentRequest != null) return
        if (!AppLockAutoPromptGate.shouldStart(
                enabled = session.state.enabled,
                locked = session.state.locked,
                deviceSecure = session.state.deviceSecure,
                authenticating = session.state.authenticating,
                hostResumed = hostResumed,
                windowFocused = windowFocused,
                alreadyAttempted = autoPromptAttempted,
            )
        ) return
        autoPromptAttempted = true
        startAuthentication(session.beginUnlock())
    }

    internal fun onHostPaused() {
        pendingExplicitIntent = null
        stoppedDuringAuthentication = currentRequest != null
        session.onHostPaused()
        debugEvent("pause")
    }

    internal fun onHostStopped(configurationChange: Boolean) {
        pendingExplicitIntent = null
        val active = currentRequest?.let { session.activeRequest(it.generation) }
        val expectedSystemAuthTransition = session.isExpectedCredentialTransition(active?.generation)
        debugEvent("stop")
        stoppedDuringAuthentication = !configurationChange && expectedSystemAuthTransition
        if (!configurationChange && !stoppedDuringAuthentication) autoPromptAttempted = false
        session.onHostStopped(configurationChange, expectedSystemAuthTransition)
        if (!configurationChange && active != null && !expectedSystemAuthTransition) {
            currentRequest = null
            credentialActivityGeneration = null
            credentialTransferPossibleGeneration = null
            coordinator?.cancelAuthentication()
        }
        publish()
    }

    internal fun onScreenOff() {
        pendingExplicitIntent = null
        debugEvent("screen_off")
        currentRequest = null
        credentialActivityGeneration = null
        credentialTransferPossibleGeneration = null
        autoPromptAttempted = false
        coordinator?.cancelAuthentication()
        session.onScreenOff()
        publish()
    }

    internal fun onUserLeftForBackground(force: Boolean = false) {
        debugEvent(if (force) "leave_forced" else "leave_hint")
        if (!session.onUserLeftForBackground(force)) return
        pendingExplicitIntent = null
        currentRequest = null
        credentialActivityGeneration = null
        credentialTransferPossibleGeneration = null
        coordinator?.cancelAuthentication()
        autoPromptAttempted = false
        publish()
    }

    internal fun onUserLeaveHint() {
        val activeGeneration = currentRequest?.generation
        val separateCredentialActivityStarted = activeGeneration != null &&
            credentialActivityGeneration == activeGeneration
        debugEvent(if (separateCredentialActivityStarted) "leave_credential_activity" else "leave_user_choice")
        if (!session.onUserLeaveHint(separateCredentialActivityStarted)) return
        pendingExplicitIntent = null
        currentRequest = null
        credentialActivityGeneration = null
        credentialTransferPossibleGeneration = null
        coordinator?.cancelAuthentication()
        autoPromptAttempted = false
        publish()
    }

    internal fun onHostDetached(owner: Long, configurationChange: Boolean): Boolean {
        if (owner != hostGeneration) return false
        coordinator = null
        pendingExplicitIntent = null
        val requestGeneration = currentRequest?.generation
        val preserveCredentialResult = configurationChange && requestGeneration != null && (
            requestGeneration == credentialActivityGeneration ||
                session.hasPendingAuthenticationResult(requestGeneration)
            )
        credentialTransferPossibleGeneration = null
        if (!preserveCredentialResult) {
            currentRequest = null
            credentialActivityGeneration = null
            // A configuration change must preserve the current unlocked/locked state.
            session.invalidateAuthentication(lockIfEnabled = !configurationChange)
            publish()
        }
        return preserveCredentialResult
    }

    internal fun onCredentialActivityStarted(owner: Long, generation: Long) {
        if (owner == hostGeneration && session.activeRequest(generation) != null) {
            credentialActivityGeneration = generation
            credentialTransferPossibleGeneration = null
            session.expectCredentialTransition(generation)
            debugEvent("credential_activity", generation)
        }
    }

    internal fun onCredentialTransferPossible(owner: Long, generation: Long) {
        if (owner == hostGeneration && session.activeRequest(generation) != null) {
            credentialTransferPossibleGeneration = generation
            session.expectCredentialTransition(generation)
            debugEvent("credential_transfer_possible", generation)
        }
    }

    internal fun onAuthenticationResult(owner: Long, generation: Long, succeeded: Boolean) {
        debugEvent("result", generation, succeeded)
        if (owner != hostGeneration) {
            debugEvent("result_stale_host", generation, succeeded)
            return
        }
        val request = session.activeRequest(generation) ?: run {
            debugEvent("result_stale_request", generation, succeeded)
            return
        }
        // The credential return can emit another stop/leave before resume. Keep its transport
        // and generation until the pending result is consumed or explicitly invalidated.
        if (!hostResumed) session.onHostPaused()
        if (session.recordAuthenticationResult(generation, succeeded)) {
            debugEvent("result_deferred", generation, succeeded)
            // Recover a resumed-controller/paused-session mismatch without waiting for another resume.
            drainPendingAuthenticationResultIfResumed()
            publish()
            return
        }
        currentRequest = request
        debugEvent("result_direct", generation, succeeded)
        applyAuthenticationResult(request, succeeded)
    }

    private fun drainPendingAuthenticationResultIfResumed() {
        if (!hostResumed) return
        // Also synchronizes the session's resumed state when no result is pending.
        val pending = session.takePendingResultAfterResume() ?: return
        if (session.activeRequest(pending.request.generation) != pending.request) {
            debugEvent("pending_stale_request", pending.request.generation, pending.succeeded)
            return
        }
        currentRequest = pending.request
        debugEvent("pending_drain", pending.request.generation, pending.succeeded)
        applyAuthenticationResult(pending.request, pending.succeeded)
    }

    private fun startAuthentication(request: AppLockAuthenticationRequest?) {
        if (request == null) {
            debugEvent("authenticate_rejected")
            return
        }
        currentRequest = request
        retryableRequestGeneration = null
        autoPromptAttempted = true
        debugEvent("authenticate")
        clearMessage()
        val currentCoordinator = coordinator
        if (currentCoordinator == null || !hostResumed) {
            debugEvent("authenticate_host_unavailable")
            session.finishAuthentication(request.generation, succeeded = false)
            currentRequest = null
            showMessage("인증을 시작하지 못했어요. 다시 시도해 주세요.")
            publish()
            return
        }
        publish()
        try {
            currentCoordinator.authenticate(request)
        } catch (_: RuntimeException) {
            debugEvent("authenticate_exception")
            currentCoordinator.cancelAuthentication()
            session.finishAuthentication(request.generation, succeeded = false)
            currentRequest = null
            credentialActivityGeneration = null
            credentialTransferPossibleGeneration = null
            showMessage("기기 인증을 시작하지 못했어요. 다시 시도해 주세요.")
            publish()
        }
    }

    private fun applyAuthenticationResult(request: AppLockAuthenticationRequest, succeeded: Boolean) {
        retryableRequestGeneration = null
        val stillSecure = coordinator?.isDeviceSecure() == true
        session.updateDeviceSecure(stillSecure)
        val accepted = succeeded && stillSecure
        var persistenceSucceeded = true
        if (accepted && request.desiredEnabled != null) {
            persistenceSucceeded = preferences.setEnabled(request.desiredEnabled)
            debugEvent("commit", request.generation, persistenceSucceeded)
        }
        session.finishAuthentication(
            generation = request.generation,
            succeeded = accepted,
            persistenceSucceeded = persistenceSucceeded,
        )
        debugEvent("result_applied", request.generation, accepted && persistenceSucceeded)
        currentRequest = null
        credentialActivityGeneration = null
        credentialTransferPossibleGeneration = null
        if (succeeded && !stillSecure) {
            showMessage("기기 화면 잠금이 없어 인증을 완료하지 못했어요.")
        } else if (succeeded && !persistenceSucceeded) {
            showMessage("잠금 설정을 저장하지 못했어요. 다시 시도해 주세요.")
        } else if (!succeeded) {
            showMessage("인증이 취소되었어요. 다시 시도해 주세요.")
        }
        publish()
    }

    private fun showMessage(value: String) {
        val generation = ++messageGeneration
        messageJob?.cancel()
        // The generation doubles as the event id, so repeating the same text announces again.
        session.setMessage(value, generation)
        publish()
        messageJob = (messageScope ?: viewModelScope).launch {
            delay(MESSAGE_LIFETIME_MILLIS)
            if (generation == messageGeneration) {
                session.setMessage(null)
                publish()
            }
        }
    }

    private fun clearMessage() {
        messageGeneration++
        messageJob?.cancel()
        messageJob = null
        session.setMessage(null)
    }

    private fun syncPersistedSetting() {
        val persistedEnabled = preferences.isEnabled()
        if (persistedEnabled == session.state.enabled) return
        currentRequest = null
        credentialActivityGeneration = null
        credentialTransferPossibleGeneration = null
        coordinator?.cancelAuthentication()
        session.syncEnabledFromPreferences(persistedEnabled)
        clearMessage()
        publish()
    }

    private fun publish() {
        val retryable = currentRequest != null &&
            currentRequest?.generation == retryableRequestGeneration
        mutableState.value = if (retryable) session.state.copy(authenticating = false) else session.state
    }

    private fun debugEvent(event: String, generation: Long? = currentRequest?.generation, result: Boolean? = null) {
        val request = currentRequest?.takeIf { it.generation == generation }
        logEvent("event=$event purpose=${request?.purpose} generation=$generation " +
            "hostResumed=$hostResumed pending=${session.hasPendingAuthenticationResult(generation)} " +
            "enabled=${session.state.enabled} authenticating=${session.state.authenticating} " +
            "deviceSecure=${session.state.deviceSecure} locked=${session.state.locked} result=$result")
    }

    internal class Factory(context: Context) : ViewModelProvider.Factory {
        private val applicationContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AppLockController::class.java))
            return AppLockController(applicationContext) as T
        }
    }

    private companion object {
        const val MESSAGE_LIFETIME_MILLIS = 5_000L
    }
}
