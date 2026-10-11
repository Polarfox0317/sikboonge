package io.github.polarfox0317.sikboonge

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import io.github.polarfox0317.sikboonge.ui.shell.AppRoot
import io.github.polarfox0317.sikboonge.ui.state.AppController
import io.github.polarfox0317.sikboonge.ui.state.AppControllerFactory
import io.github.polarfox0317.sikboonge.ui.state.Destination
import io.github.polarfox0317.sikboonge.ui.welcome.WelcomePreferences

class MainActivity : ComponentActivity() {
    private lateinit var controller: AppController
    private var incomingUri by mutableStateOf<String?>(null)
    private var incomingEventToken by mutableIntStateOf(0)
    private var openLibraryEventToken by mutableIntStateOf(0)
    private var openUpdateEventToken by mutableIntStateOf(0)
    private var consumedOpenLibraryEventToken = 0
    private var consumedOpenUpdateEventToken = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WelcomePreferences.evaluateOnLaunch(this)
        val forceWelcome = if (savedInstanceState == null) {
            BuildConfig.DEBUG && intent.getBooleanExtra("debug_show_welcome", false)
        } else {
            false
        }
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        AppStore.load(this)
        controller = ViewModelProvider(
            this,
            AppControllerFactory(applicationContext),
        )[AppController::class.java]
        if (savedInstanceState != null) {
            incomingUri = savedInstanceState.getString("incomingUri")
            incomingEventToken = savedInstanceState.getInt("incomingEventToken")
            openLibraryEventToken = savedInstanceState.getInt("openLibraryEventToken")
            openUpdateEventToken = savedInstanceState.getInt("openUpdateEventToken")
            consumedOpenLibraryEventToken = savedInstanceState.getInt("consumedOpenLibraryEventToken")
            consumedOpenUpdateEventToken = savedInstanceState.getInt("consumedOpenUpdateEventToken")
        } else {
            accept(intent)
        }
        setContent {
            AppRoot(controller, forceWelcome = forceWelcome)
            LaunchedEffect(incomingEventToken) {
                controller.actions.onSharedIntent(incomingUri, incomingEventToken)
            }
            LaunchedEffect(openLibraryEventToken) {
                if (openLibraryEventToken > consumedOpenLibraryEventToken) {
                    consumedOpenLibraryEventToken = openLibraryEventToken
                    controller.actions.onTopLevelSelected(Destination.Library)
                }
            }
            LaunchedEffect(openUpdateEventToken) {
                if (openUpdateEventToken > consumedOpenUpdateEventToken) {
                    consumedOpenUpdateEventToken = openUpdateEventToken
                    controller.actions.onOpenSettings()
                }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("incomingUri", incomingUri)
        outState.putInt("incomingEventToken", incomingEventToken)
        outState.putInt("openLibraryEventToken", openLibraryEventToken)
        outState.putInt("openUpdateEventToken", openUpdateEventToken)
        outState.putInt("consumedOpenLibraryEventToken", consumedOpenLibraryEventToken)
        outState.putInt("consumedOpenUpdateEventToken", consumedOpenUpdateEventToken)
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); accept(intent) }
    override fun onResume() { super.onResume(); BotRuntime.setup(this) }
    private fun accept(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND) {
            val streamUri = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            incomingUri = (streamUri ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: intent.data)?.toString()
            incomingEventToken++
        }
        if (intent.getBooleanExtra("open_library", false)) openLibraryEventToken++
        if (intent.getBooleanExtra("open_update", false)) openUpdateEventToken++
    }
}
