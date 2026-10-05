package dev.pinkcollab.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos

@Composable
internal fun TimelineLoadingState(
    modifier: Modifier = Modifier,
) {
    val phase = rememberInfiniteTransition(label = "timelineSkeleton").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "skeletonBreath",
    )
    Column(modifier.fillMaxSize()) {
        // Loading is announced once by the work-status line, not by decorative placeholders.
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            repeat(3) { index ->
                val firstPlaceholder = index * 3 + if (index > 1) 1 else 0
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.width(if (index == 1) 72.dp else 48.dp).height(10.dp)
                        .skeletonBreathing(phase, firstPlaceholder)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f), RoundedCornerShape(5.dp)))
                    repeat(if (index == 1) 3 else 2) { line ->
                        Spacer(Modifier.fillMaxWidth(if (line == 1) 0.65f else 0.9f).height(12.dp)
                            .skeletonBreathing(phase, firstPlaceholder + line + 1)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(6.dp)))
                    }
                }
            }
        }
    }
}

/** One shared clock, ten staggered phases; opacity changes never recompose the placeholder layout. */
private fun Modifier.skeletonBreathing(phase: State<Float>, index: Int): Modifier = graphicsLayer {
    val wave = (0.5 - 0.5 * cos(2 * PI * (phase.value - index / 10f))).toFloat()
    alpha = 0.45f + 0.55f * wave
}
