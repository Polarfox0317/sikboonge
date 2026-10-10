package io.github.polarfox0317.sikboonge

import androidx.compose.runtime.Composable

/** Release builds intentionally collect and export no diagnostic data. */
internal object AppDiagnostics {
    @Suppress("UNUSED_PARAMETER")
    fun event(category: String, message: String) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun failure(category: String, error: Throwable) = Unit
}

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun rememberDiagnosticLogExporter(onFeedback: (String, Boolean) -> Unit): (() -> Unit)? = null
