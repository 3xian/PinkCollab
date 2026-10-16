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
import kotlin.math.sqrt

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

/** Distinct filled silhouettes pause briefly between smooth, continuous morphs. */
@Composable
internal fun MorphIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val phase = rememberInfiniteTransition(label = "morphIndicator").animateFloat(
        0f, SHAPES.toFloat(), infiniteRepeatable(tween(2_800, easing = LinearEasing)), label = "morphPhase",
    )
    val path = remember { Path() }
    IndicatorCanvas(modifier.size(48.dp)) {
        val cycle = phase.value
        val from = cycle.toInt() % SHAPES
        // Each 700ms stage spends 420ms morphing and 280ms holding its endpoints.
        val local = ((cycle - cycle.toInt() - 0.20f) / 0.60f).coerceIn(0f, 1f)
        val eased = local * local * (3f - 2f * local)
        val start = MorphOutlines[from]
        val end = MorphOutlines[(from + 1) % SHAPES]
        val radius = size.minDimension * 0.42f
        path.reset()
        for (step in 0 until MORPH_STEPS) {
            val x = center.x + radius * (start[step * 2] + (end[step * 2] - start[step * 2]) * eased)
            val y = center.y + radius * (start[step * 2 + 1] + (end[step * 2 + 1] - start[step * 2 + 1]) * eased)
            if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
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
private const val MORPH_STEPS = 64

// Sample once, not on every animation frame. All outlines use matching radial
// points so interpolation stays continuous, including the last-to-first morph.
private val MorphOutlines = Array(SHAPES) { shape ->
    FloatArray(MORPH_STEPS * 2).apply {
        repeat(MORPH_STEPS) { step ->
            val angle = step / MORPH_STEPS.toFloat() * (2f * PI.toFloat())
            val x = cos(angle)
            val y = sin(angle)
            val radius = when (shape) {
                0 -> 1f
                1 -> 0.84f / sqrt(sqrt(x * x * x * x + y * y * y * y)) // Rounded square.
                2 -> 0.68f + 0.32f * cos(3f * angle) // Deep three-lobed silhouette.
                else -> 1f / sqrt(x * x + y * y / (0.42f * 0.42f)) // Flattened ellipse.
            }
            this[step * 2] = x * radius
            this[step * 2 + 1] = y * radius
        }
    }
}
