package io.github.polarfox0317.sikboonge.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun rememberMediaArtworkShape(playing: Boolean): Shape {
    val morph = remember { Morph(MaterialShapes.Square, MaterialShapes.Cookie9Sided) }
    val progress by animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "mediaArtworkShape",
    )
    return remember(morph, progress) {
        object : Shape {
            override fun createOutline(
                size: Size,
                layoutDirection: LayoutDirection,
                density: Density,
            ): Outline {
                // beta01 provides Morph.toPath, but does not provide Morph.toShape.
                val path = morph.toPath(progress.coerceIn(0f, 1f))
                path.transform(Matrix().apply { scale(size.width, size.height) })
                path.translate(size.center - path.getBounds().center)
                return Outline.Generic(path)
            }
        }
    }
}
