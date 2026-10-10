package io.github.polarfox0317.sikboonge.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal object AppDimens {
    // app choice: readable media columns at the normal system font size.
    val minGridColumnWidth = 320.dp
    // app choice: minimum width for a compact media tile.
    val minTileWidth = 160.dp
    // app choice: keep input forms readable in wider windows.
    val maxFormWidth = 640.dp
    // app choice: compact screen edge spacing.
    val compactHorizontalPadding = 16.dp
    // app choice: medium screen edge spacing.
    val mediumHorizontalPadding = 24.dp
    // app choice: expanded screen edge spacing.
    val expandedHorizontalPadding = 32.dp
    // app choice: separate independent content sections.
    val sectionSpacing = 24.dp
    // app choice: spacing and padding within app content units.
    val itemSpacing = 16.dp
    // app choice: a compact, square artwork beside media metadata.
    val mediaThumbnailSize = 88.dp
    // app choice: decorative empty-state artwork with clear visual presence.
    val emptyStateShapeSize = 120.dp
    // app choice: inset for the selection badge within artwork.
    val selectionBadgeInset = 4.dp
    // app choice: normalized check-mark geometry for the decorative selection icon.
    const val checkStartX = 0.22f
    // app choice: normalized check-mark geometry.
    const val checkStartY = 0.50f
    // app choice: normalized check-mark geometry.
    const val checkMiddleX = 0.42f
    // app choice: normalized check-mark geometry.
    const val checkMiddleY = 0.70f
    // app choice: normalized check-mark geometry.
    const val checkEndX = 0.78f
    // app choice: normalized check-mark geometry.
    const val checkEndY = 0.30f
    // app choice: check stroke relative to the Material icon preset size.
    const val checkStrokeRatio = 0.08f

    fun minimumGridColumnWidth(fontScale: Float): Dp =
        minGridColumnWidth * fontScale.coerceAtLeast(1f)

    fun minimumTileWidth(fontScale: Float): Dp =
        minTileWidth * fontScale.coerceAtLeast(1f)

    fun contentHorizontalPadding(tier: WindowTier): Dp = when (tier) {
        WindowTier.Compact -> compactHorizontalPadding
        WindowTier.Medium -> mediumHorizontalPadding
        WindowTier.Expanded -> expandedHorizontalPadding
    }
}
