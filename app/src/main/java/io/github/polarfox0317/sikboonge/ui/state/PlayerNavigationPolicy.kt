package io.github.polarfox0317.sikboonge.ui.state

/** A tab change collapses a visible expanded player; settings and tab reselection preserve it. */
internal fun shouldCollapsePlayerForTopLevelNavigation(
    currentDestination: Destination,
    currentTopLevelDestination: Destination,
    selectedDestination: Destination,
    playerExpanded: Boolean,
): Boolean = playerExpanded &&
    currentDestination != Destination.Settings &&
    currentTopLevelDestination != selectedDestination
