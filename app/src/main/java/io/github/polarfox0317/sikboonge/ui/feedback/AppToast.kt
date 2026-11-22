package io.github.polarfox0317.sikboonge.ui.feedback

import android.content.Context
import android.widget.Toast

/**
 * **App design choice (user request):** one-time in-app notices use the standard OS text toast, so
 * the system owns placement, foreground/background and rate policy, timeout and accessibility
 * timing.
 *
 * A single presenter is used app-wide. Showing a new notice cancels the toast still on screen and
 * replaces it, so repeated actions never queue a backlog of stale messages. Only the application
 * context is used, so no Activity is retained, and a plain text toast has no action button or
 * custom view.
 */
internal object AppToast {
    private var current: Toast? = null

    fun show(context: Context, text: String) {
        if (text.isBlank()) return
        val appContext = context.applicationContext ?: context
        current?.cancel()
        current = Toast.makeText(appContext, text, Toast.LENGTH_SHORT).also { toast ->
            runCatching { toast.show() }
        }
    }
}
