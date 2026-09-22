package dev.pinkcollab.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.pinkcollab.ui.theme.Purple200
import dev.pinkcollab.ui.theme.Purple400
import dev.pinkcollab.ui.theme.Teal300

@Composable
internal fun TimelineLoadingState(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "timelineLoading")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_450, easing = LinearEasing),
        ),
        label = "timelineShimmer",
    )

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(TimelineBandBase)
            .progressSemantics(),
    ) {
        val density = LocalDensity.current
        val viewportWidth = with(density) { maxWidth.toPx() }
        val shimmerWidth = with(density) { 180.dp.toPx() }
        val shimmerCenter = -shimmerWidth + progress * (viewportWidth + shimmerWidth * 2f)
        val shimmer = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                Purple200.copy(alpha = 0.08f),
                Color.White.copy(alpha = 0.18f),
                Purple200.copy(alpha = 0.08f),
                Color.Transparent,
            ),
            start = Offset(shimmerCenter - shimmerWidth, 0f),
            end = Offset(shimmerCenter + shimmerWidth, 0f),
        )

        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TimelinePlaceholderBand(
                tint = Purple400,
                titleWidth = 0.22f,
                lineWidths = listOf(0.88f, 0.62f),
                shimmer = shimmer,
            )
            TimelinePlaceholderBand(
                tint = Teal300,
                titleWidth = 0.30f,
                lineWidths = listOf(0.94f, 0.78f, 0.48f),
                shimmer = shimmer,
            )
            TimelinePlaceholderBand(
                tint = Purple400,
                titleWidth = 0.18f,
                lineWidths = listOf(0.72f, 0.44f),
                shimmer = shimmer,
            )
        }
    }
}

@Composable
private fun TimelinePlaceholderBand(
    tint: Color,
    titleWidth: Float,
    lineWidths: List<Float>,
    shimmer: Brush,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .timelineBand(tint = tint, tintAlpha = 0.045f)
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(tint.copy(alpha = 0.52f), CircleShape),
            )
            Spacer(Modifier.width(9.dp))
            LoadingLine(
                Modifier
                    .fillMaxWidth(titleWidth)
                    .height(9.dp),
                shimmer,
            )
        }
        lineWidths.forEach { width ->
            LoadingLine(
                Modifier
                    .fillMaxWidth(width)
                    .height(11.dp),
                shimmer,
            )
        }
    }
}

@Composable
private fun LoadingLine(modifier: Modifier, shimmer: Brush) {
    Box(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.065f))
            .background(shimmer),
    )
}
