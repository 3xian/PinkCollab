package dev.pinkcollab.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

private val supportsBackdropBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
private val BackdropFadeHeight = 32.dp

/** Capture only pages that are visible, including both pages during a swipe. */
internal fun Modifier.composerBackdropSource(state: HazeState, isVisible: Boolean): Modifier =
    if (supportsBackdropBlur && isVisible) hazeSource(state) else this

@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun ComposerBackdrop(
    state: HazeState,
    clearance: Dp,
    isVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val height = clearance + BackdropFadeHeight
    val fadePx = with(LocalDensity.current) { BackdropFadeHeight.toPx() }
    val backdropColor = MaterialTheme.colorScheme.background
    val blur = if (supportsBackdropBlur && isVisible) Modifier.hazeEffect(state) {
        backgroundColor = backdropColor
        tints = listOf(HazeTint(Color.Transparent))
        blurRadius = 12.dp
        noiseFactor = 0f
        inputScale = HazeInputScale.Fixed(0.5f)
        // Fade a uniform blur instead of recalculating a progressive blur.
        mask = Brush.verticalGradient(
            colors = listOf(Color.Transparent, Color.Black),
            endY = fadePx,
        )
    } else Modifier

    // Visibility controls rendering work, never the platform's visual style.
    val scrim = Brush.verticalGradient(
        0f to Color.Transparent,
        (BackdropFadeHeight / height) to Color.Black.copy(
            alpha = if (supportsBackdropBlur) 0.28f else 0.60f,
        ),
        1f to Color.Black.copy(alpha = if (supportsBackdropBlur) 0.50f else 1f),
    )
    Box(modifier.fillMaxWidth().height(height).clipToBounds().then(blur).background(scrim))
}
