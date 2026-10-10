package io.github.polarfox0317.sikboonge

/** Keeps bot-save work out of the local conversion result surface. */
internal object ConversionPresentationPolicy {
    fun showLocalProgress(state: ConversionState): Boolean =
        state.origin == ConversionOrigin.LOCAL_CONVERT && state.running

    fun localResult(state: ConversionState): AudioResult? =
        state.result.takeIf { state.origin == ConversionOrigin.LOCAL_CONVERT }
}
