package dev.pinkcollab.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Three dots chase a circle. Used in the 18dp slots that used to spin a ring. */
@Composable
internal fun OrbitDots(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val angle = rememberInfiniteTransition(label = "orbitDots").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "orbitAngle",
    )
    IndicatorCanvas(modifier.size(18.dp)) {
        val rotation = angle.value
        val orbit = size.minDimension * 0.30f
        repeat(3) { index ->
            val theta = (rotation + index * 120f) * (PI / 180.0)
            drawCircle(
                color.copy(alpha = 1f - index * 0.34f),
                radius = size.minDimension * (0.16f - index * 0.035f),
                center = center + Offset(cos(theta).toFloat() * orbit, sin(theta).toFloat() * orbit),
            )
        }
    }
}

/** A filled silhouette eases between rounded shapes. Replaces the larger ring indicators. */
@Composable
internal fun MorphIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val phase = rememberInfiniteTransition(label = "morphIndicator").animateFloat(
        0f, SHAPES.toFloat(), infiniteRepeatable(tween(3_200, easing = LinearEasing)), label = "morphPhase",
    )
    val path = remember { Path() }
    IndicatorCanvas(modifier.size(48.dp)) {
        val cycle = phase.value
        val from = cycle.toInt() % SHAPES
        val local = cycle - cycle.toInt()
        val eased = local * local * (3f - 2f * local)
        val radius = size.minDimension * 0.42f
        path.reset()
        val steps = 56
        for (step in 0..steps) {
            val angle = step / steps.toFloat() * (2f * PI.toFloat())
            val fromRadius = shapeRadius(from, angle)
            val extent = fromRadius + (shapeRadius(from + 1, angle) - fromRadius) * eased
            val point = center + Offset(cos(angle) * radius * extent, sin(angle) * radius * extent)
            if (step == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        path.close()
        drawPath(path, color)
    }
}

@Composable
private fun IndicatorCanvas(
    modifier: Modifier,
    onDraw: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
) {
    Canvas(
        modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
        onDraw = onDraw,
    )
}

private const val SHAPES = 4

/** Radius relative to a circle. Consecutive shapes meet so the loop does not pop. */
private fun shapeRadius(shape: Int, angle: Float): Float = when (Math.floorMod(shape, SHAPES)) {
    0 -> 1f
    1 -> 0.84f + 0.16f * kotlin.math.abs(cos(2f * angle))
    2 -> 0.78f + 0.22f * (0.5f + 0.5f * cos(3f * angle))
    else -> 0.82f + 0.12f * cos(2f * angle) + 0.06f * sin(angle)
}
