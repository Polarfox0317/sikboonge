package io.github.polarfox0317.sikboonge.security

import android.content.Context
import android.util.Log
import io.github.polarfox0317.sikboonge.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppLockController internal constructor(context: Context) : ViewModel() {
    private val preferences = AppLockPreferences(context.applicationContext)
    private val session = AppLockSession(
        enabled = preferences.isEnabled(),
        deviceSecure = false,
    )
    private val mutableState = MutableStateFlow(session.state)
    val state: StateFlow<AppLockUiState> = mutableState.asStateFlow()

    private var hostGeneration = 0L
    private var coordinator: AppLockSystemAuthCoordinator? = null
    private var hostResumed = false
    private var autoPromptAttempted = false
    private var stoppedDuringAuthentication = false
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
        if (!session.state.deviceSecure) {
            debugEvent("request_device_insecure")
            showMessage("기기 화면 잠금을 설정한 뒤 사용할 수 있어요.")
            return
        }
        startAuthentication(session.beginEnabledChange(enabled))
    }

    fun unlock() {
        debugEvent("unlock")
        if (!session.state.deviceSecure) {
            showMessage("기기 화면 잠금을 설정한 뒤 잠금을 해제할 수 있어요.")
            return
        }
        startAuthentication(session.beginUnlock())
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
        if (stoppedDuringAuthentication) {
            stoppedDuringAuthentication = false
        }
    }

    internal fun onHostResumed() {
        hostResumed = true
        debugEvent("resume")
        syncPersistedSetting()
        session.updateDeviceSecure(coordinator?.isDeviceSecure() == true)
        drainPendingAuthenticationResultIfResumed()
        publish()

        debugEvent("resume_complete")

        if (!session.state.enabled || !session.state.locked || !session.state.deviceSecure ||
            session.state.authenticating || autoPromptAttempted
        ) return
        autoPromptAttempted = true
        startAuthentication(session.beginUnlock(), automatic = true)
    }

    internal fun onHostPaused() {
        hostResumed = false
        session.onHostPaused()
        debugEvent("pause")
    }

    internal fun onHostStopped(configurationChange: Boolean) {
        hostResumed = false
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
        hostResumed = false
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
        hostResumed = false
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
        hostResumed = false
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

    private fun startAuthentication(request: AppLockAuthenticationRequest?, automatic: Boolean = false) {
        if (request == null) {
            debugEvent("authenticate_rejected")
            return
        }
        currentRequest = request
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
        session.setMessage(value)
        publish()
        messageJob = viewModelScope.launch {
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
        mutableState.value = session.state
    }

    private fun debugEvent(event: String, generation: Long? = currentRequest?.generation, result: Boolean? = null) {
        if (!BuildConfig.DEBUG) return
        val request = currentRequest?.takeIf { it.generation == generation }
        Log.d("AppLock", "event=$event purpose=${request?.purpose} generation=$generation " +
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
