package io.github.polarfox0317.sikboonge.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Shared host for the production and local review apps. */
abstract class AppLockActivity : ComponentActivity() {
    lateinit var appLockController: AppLockController
        private set

    private var authCoordinator: AndroidSystemAuthCoordinator? = null
    private var hostToken: Long = 0L
    private var backgroundSignalsRegistered = false

    private val backgroundSignalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> appLockController.onScreenOff()
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                    val reason = intent.getStringExtra("reason")
                    if (reason == "homekey" || reason == "recentapps") {
                        appLockController.onUserLeftForBackground(force = true)
                    }
                }
            }
        }
    }

    private val credentialLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        authCoordinator?.onCredentialActivityResult(result.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appLockController = ViewModelProvider(
            this,
            AppLockController.Factory(applicationContext),
        )[AppLockController::class.java]
        hostToken = appLockController.attachHost()
        authCoordinator = AndroidSystemAuthCoordinator(
            activity = this,
            credentialLauncher = credentialLauncher,
            onResult = { generation, succeeded ->
                appLockController.onAuthenticationResult(hostToken, generation, succeeded)
            },
            onCredentialStarted = { generation ->
                appLockController.onCredentialActivityStarted(hostToken, generation)
            },
            onCredentialTransferPossible = { generation ->
                appLockController.onCredentialTransferPossible(hostToken, generation)
            },
        ).also { appLockController.attachCoordinator(hostToken, it) }
        registerBackgroundSignals()
        syncSecureWindow(appLockController.state.value.enabled)
        lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            appLockController.state.collect { syncSecureWindow(it.enabled) }
        }
    }

    override fun onStart() {
        appLockController.onHostStarted()
        super.onStart()
    }

    override fun onResume() {
        super.onResume()
        appLockController.onHostResumed()
        syncSecureWindow(appLockController.state.value.enabled)
    }

    override fun onStop() {
        appLockController.onHostStopped(isChangingConfigurations)
        super.onStop()
    }

    override fun onPause() {
        appLockController.onHostPaused()
        super.onPause()
    }

    override fun onUserLeaveHint() {
        appLockController.onUserLeftForBackground()
        super.onUserLeaveHint()
    }

    override fun onDestroy() {
        val preserveCredentialActivity = appLockController.onHostDetached(hostToken, isChangingConfigurations)
        authCoordinator?.detach(isChangingConfigurations, preserveCredentialActivity)
        authCoordinator = null
        if (backgroundSignalsRegistered) {
            runCatching { unregisterReceiver(backgroundSignalReceiver) }
            backgroundSignalsRegistered = false
        }
        super.onDestroy()
    }

    private fun registerBackgroundSignals() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(backgroundSignalReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(backgroundSignalReceiver, filter)
        }
        backgroundSignalsRegistered = true
    }

    private fun syncSecureWindow(enabled: Boolean) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
