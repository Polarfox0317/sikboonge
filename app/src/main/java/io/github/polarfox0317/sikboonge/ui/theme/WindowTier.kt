package io.github.polarfox0317.sikboonge.ui.theme

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.window.core.layout.WindowSizeClass

internal enum class WindowTier { Compact, Medium, Expanded }

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun rememberWindowTier(): WindowTier {
    val sizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    return when {
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) ->
            WindowTier.Expanded
        sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) ->
            WindowTier.Medium
        else -> WindowTier.Compact
    }
}
