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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
                        // This signal can arrive after a Recents return has already resumed us;
                        // acting on it would leave the session marked as backgrounded.
                        if (AppLockBackgroundSignalPolicy.shouldInvalidateFromSystemDialogSignal(
                                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                            )
                        ) {
                            appLockController.onUserLeftForBackground(force = true)
                        }
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
        // Lifecycle reaches RESUMED after Activity.onResume on current Android versions.
        // Run reconciliation when the live state queried by the coordinator really is resumed.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                appLockController.onHostResumed()
                syncSecureWindow(appLockController.state.value.enabled)
            }
        })
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

    /**
     * A resumed Activity is not necessarily the focused window (a Recents return resumes before
     * focus arrives), so the automatic prompt is released here instead of in onResume.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        appLockController.onWindowFocusChanged(hasFocus)
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
        appLockController.onUserLeaveHint()
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
