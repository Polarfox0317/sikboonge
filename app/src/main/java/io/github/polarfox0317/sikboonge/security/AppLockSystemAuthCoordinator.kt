package io.github.polarfox0317.sikboonge.security

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings
import android.util.Log
import io.github.polarfox0317.sikboonge.BuildConfig
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import android.hardware.biometrics.BiometricPrompt
import android.hardware.biometrics.BiometricManager

internal interface AppLockSystemAuthCoordinator {
    fun isDeviceSecure(): Boolean
    fun authenticate(request: AppLockAuthenticationRequest)
    fun reattachCredentialActivity(generation: Long)
    fun cancelAuthentication()
    fun openSecuritySettings(): Boolean
}

/** Platform-only adapter for biometric and device-credential authentication. */
internal class AndroidSystemAuthCoordinator(
    private val activity: ComponentActivity,
    private val credentialLauncher: ActivityResultLauncher<Intent>,
    private val onResult: (generation: Long, succeeded: Boolean) -> Unit,
    private val onCredentialStarted: (generation: Long) -> Unit,
    private val onCredentialTransferPossible: (generation: Long) -> Unit,
) : AppLockSystemAuthCoordinator {
    private val keyguard = activity.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private var activeGeneration: Long? = null
    private var biometricPrompt: BiometricPrompt? = null
    private var cancellationSignal: CancellationSignal? = null
    private var credentialActivityGeneration: Long? = null

    override fun isDeviceSecure(): Boolean = keyguard.isDeviceSecure

    override fun authenticate(request: AppLockAuthenticationRequest) {
        activeGeneration = request.generation
        credentialActivityGeneration = null
        debugEvent("authenticate", request.generation, request.purpose)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            launchCredentialActivity(request.generation)
            return
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                debugEvent("callback_success", request.generation, request.purpose, succeeded = true)
                if (activeGeneration == request.generation &&
                    credentialActivityGeneration != request.generation
                ) {
                    debugEvent("success_forward", request.generation, request.purpose, succeeded = true)
                    activeGeneration = null
                    cancellationSignal = null
                    biometricPrompt = null
                    onResult(request.generation, true)
                } else {
                    debugEvent("success_ignored", request.generation, request.purpose, succeeded = true)
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                debugEvent("callback_error", request.generation, request.purpose, errorCode = errorCode)
                if (activeGeneration != request.generation ||
                    credentialActivityGeneration == request.generation
                ) {
                    debugEvent("error_ignored", request.generation, request.purpose, errorCode = errorCode)
                    return
                }

                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P && isApi28CredentialFallback(errorCode)) {
                    launchCredentialActivity(request.generation)
                    return
                }
                debugEvent("error_forward", request.generation, request.purpose, succeeded = false, errorCode = errorCode)
                activeGeneration = null
                cancellationSignal = null
                biometricPrompt = null
                onResult(request.generation, false)
            }

            override fun onAuthenticationFailed() {
                debugEvent("callback_failed_nonterminal", request.generation, request.purpose)
            }
        }
        val builder = buildBiometricPrompt(request)
        val prompt = builder.build()
        val signal = CancellationSignal()
        biometricPrompt = prompt
        cancellationSignal = signal
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android may stop this Activity while transferring the combined prompt to PIN/pattern.
            onCredentialTransferPossible(request.generation)
        }
        prompt.authenticate(signal, activity.mainExecutor, callback)
        debugEvent("prompt_started", request.generation, request.purpose)
    }

    override fun reattachCredentialActivity(generation: Long) {
        activeGeneration = generation
        debugEvent("credential_reattached", generation)
    }

    override fun cancelAuthentication() {
        val generation = activeGeneration
        debugEvent("cancel", generation)
        val signal = cancellationSignal
        // Clear the generation first so cancellation callbacks cannot complete an obsolete request.
        activeGeneration = null
        credentialActivityGeneration = null
        cancellationSignal = null
        biometricPrompt = null
        runCatching { signal?.cancel() }
        debugEvent("cancel_complete", generation)
    }

    override fun openSecuritySettings(): Boolean = try {
        activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        true
    } catch (_: RuntimeException) {
        false
    }

    fun onCredentialActivityResult(succeeded: Boolean) {
        debugEvent("credential_result", succeeded = succeeded)
        val generation = activeGeneration ?: run {
            debugEvent("credential_result_ignored", succeeded = succeeded)
            return
        }
        debugEvent("credential_result_forward", generation, succeeded = succeeded)
        activeGeneration = null
        credentialActivityGeneration = null
        onResult(generation, succeeded)
    }

    fun detach(configurationChange: Boolean, preserveCredentialActivity: Boolean) {
        debugEvent(if (configurationChange && preserveCredentialActivity) "detach_preserving_result" else "detach_cancel")
        if (!configurationChange || !preserveCredentialActivity) {
            cancelAuthentication()
            return
        }

        // The ViewModel carries the explicit credential Activity result across recreation.
        // Release this Activity's prompt and callback; the new coordinator reattaches the generation.
        activeGeneration = null
        credentialActivityGeneration = null
        val signal = cancellationSignal
        cancellationSignal = null
        biometricPrompt = null
        runCatching { signal?.cancel() }
    }

    private fun buildBiometricPrompt(request: AppLockAuthenticationRequest): BiometricPrompt.Builder {
        val builder = BiometricPrompt.Builder(activity)
            .setTitle("앱 잠금")
            .setSubtitle(authenticationSubtitle(request))

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                @Suppress("DEPRECATION")
                builder.setDeviceCredentialAllowed(true)
            }
            else -> {
                builder.setNegativeButton("기기 잠금 사용", activity.mainExecutor) { _, _ ->
                    launchCredentialActivity(request.generation)
                }
            }
        }
        return builder
    }

    private fun launchCredentialActivity(generation: Long) {
        debugEvent("credential_launch", generation)
        val challenge = try {
            @Suppress("DEPRECATION")
            keyguard.createConfirmDeviceCredentialIntent("앱 잠금", "기기 잠금으로 인증해 주세요.")
        } catch (_: RuntimeException) {
            null
        }
        if (challenge == null) {
            debugEvent("credential_unavailable", generation, succeeded = false)
            if (activeGeneration == generation) {
                activeGeneration = null
                credentialActivityGeneration = null
                onResult(generation, false)
            }
            return
        }
        credentialActivityGeneration = generation
        onCredentialStarted(generation)
        try {
            credentialLauncher.launch(challenge)
            debugEvent("credential_launched", generation)
        } catch (_: RuntimeException) {
            debugEvent("credential_launch_failed", generation, succeeded = false)
            activeGeneration = null
            credentialActivityGeneration = null
            onResult(generation, false)
        }
    }

    private fun isApi28CredentialFallback(errorCode: Int): Boolean = when (errorCode) {
        BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS,
        BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT,
        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT,
        BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT -> true
        else -> false
    }

    private fun authenticationSubtitle(request: AppLockAuthenticationRequest): String = when (request.purpose) {
        AppLockAuthenticationPurpose.Unlock -> "앱 잠금을 해제해 주세요."
        AppLockAuthenticationPurpose.Enable -> "앱 잠금을 사용하려면 인증해 주세요."
        AppLockAuthenticationPurpose.Disable -> "앱 잠금을 끄려면 인증해 주세요."
    }

    private fun debugEvent(
        event: String,
        generation: Long? = activeGeneration,
        purpose: AppLockAuthenticationPurpose? = null,
        succeeded: Boolean? = null,
        errorCode: Int? = null,
    ) {
        if (!BuildConfig.DEBUG) return
        Log.d("AppLock", "adapter=$event purpose=$purpose generation=$generation activeGeneration=$activeGeneration " +
            "credentialActivity=${credentialActivityGeneration != null} result=$succeeded errorCode=$errorCode")
    }
}
