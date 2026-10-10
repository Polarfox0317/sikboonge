package io.github.polarfox0317.sikboonge

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import io.github.polarfox0317.sikboonge.ui.shell.AppRoot
import io.github.polarfox0317.sikboonge.ui.state.AppController
import io.github.polarfox0317.sikboonge.ui.state.AppControllerFactory

/** New UI host; former synthetic fixtures are archived until visual acceptance. */
class NavigationRestoreFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        val controller = ViewModelProvider(
            this,
            AppControllerFactory(applicationContext),
        )[AppController::class.java]
        setContent { AppRoot(controller) }
    }
}
