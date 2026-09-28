package dev.pinkcollab.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
internal fun TimelineLoadingState(modifier: Modifier = Modifier) {
    val baseColor = MaterialTheme.colorScheme.onSurfaceVariant
    val highlightColor = MaterialTheme.colorScheme.onSurface
    val transition = rememberInfiniteTransition(label = "sessionLoadingShimmer")
    val sweep = transition.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing)),
        label = "sessionLoadingSweep",
    )
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        AnimatedLoadingLogo(logoSize = 144.dp, animateGlow = false, glowScale = 0.8f)
        Text(
            "Loading session",
            style = MaterialTheme.typography.bodySmall,
            color = baseColor,
            modifier = Modifier
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val center = size.width * sweep.value
                    val halfWidth = size.width * 0.35f
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(baseColor, highlightColor, baseColor),
                            start = Offset(center - halfWidth, 0f),
                            end = Offset(center + halfWidth, 0f),
                        ),
                        blendMode = BlendMode.SrcIn,
                    )
                },
        )
    }
}
