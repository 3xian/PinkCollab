package dev.pinkcollab.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.pinkcollab.ui.theme.BrandPurple
import kotlin.math.PI
import kotlin.math.cos

/** Draw-time animation keeps the glow out of composition and the slider center steady. */
@Composable
internal fun ThinkingGlowThumb(enabled: Boolean, modifier: Modifier = Modifier) {
    val phase = if (enabled) {
        rememberInfiniteTransition(label = "thinkingGlow").animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)),
            label = "glowPulse",
        )
    } else null
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Canvas(modifier) {
        val time = phase?.value ?: 0f
        val unit = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = unit * 0.27f
        if (enabled) {
            val pulse = (1f - cos(time)) / 2f
            val glowRadius = unit * (0.42f + pulse * 0.07f)
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to BrandPurple.copy(alpha = 0.2f + pulse * 0.3f),
                        0.5f to BrandPurple.copy(alpha = 0.14f + pulse * 0.22f),
                        1f to Color.Transparent,
                    ),
                    center = center,
                    radius = glowRadius,
                ),
                radius = glowRadius,
                center = center,
            )
        }
        drawCircle(
            brush = Brush.radialGradient(
                colors = if (enabled) listOf(Color(0xFFB6A0F0), BrandPurple, Color(0xFF6345B5))
                    else listOf(muted, muted),
                center = center - Offset(radius * 0.35f, radius * 0.4f),
                radius = radius * 1.6f,
            ),
            radius = radius,
            center = center,
        )
    }
}
