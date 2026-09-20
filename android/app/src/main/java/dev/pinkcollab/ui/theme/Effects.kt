package dev.pinkcollab.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val BrandGradient: Brush = Brush.linearGradient(listOf(BrandPurple, BrandPink))

/** Full-bleed near-black canvas with restrained brand-color glow orbs. */
@Composable
fun GlowBackground(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRect(Base0)
        // Violet bloom anchors the navigation area.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(BrandPurple.copy(alpha = 0.18f), Color.Transparent),
                center = Offset(size.width * 0.12f, -size.height * 0.08f),
                radius = size.minDimension * 1.05f,
            ),
        )
        // Orchid bloom gives the content depth without lifting the black base.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(BrandPink.copy(alpha = 0.14f), Color.Transparent),
                center = Offset(size.width * 1.02f, size.height * 0.95f),
                radius = size.minDimension * 0.95f,
            ),
        )
        // faint deep-purple floor glow at the bottom center
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Purple700.copy(alpha = 0.24f), Color.Transparent),
                center = Offset(size.width * 0.5f, size.height * 1.12f),
                radius = size.width * 0.85f,
            ),
        )
    }
}

/** Brand-gradient hairline: white edges read as grey against the near-black base. */
private fun edgeBrush(alpha: Float): Brush =
    Brush.linearGradient(listOf(BrandPurple.copy(alpha = alpha), BrandPink.copy(alpha = alpha)))

/**
 * Frosted-glass panel: translucent gradient fill plus a purple→violet hairline border.
 * Drawn over [GlowBackground] it reads as glass without paying per-node blur cost,
 * which keeps long scrolling lists smooth.
 *
 * The outline is derived from [shape], so pills and cards each get a matching corner
 * radius; a fixed radius makes the stroke drift off the corner and show a stray edge.
 */
fun Modifier.glassPanel(
    shape: Shape,
    fillAlpha: Float = 0.07f,
    borderAlpha: Float = 0.14f,
    borderBrush: Brush? = null,
): Modifier = background(
    Brush.linearGradient(listOf(BrandPurple.copy(alpha = fillAlpha), BrandPink.copy(alpha = fillAlpha * 0.72f))),
    shape,
).border(1.2.dp, borderBrush ?: edgeBrush(borderAlpha), shape)

@Composable
private fun rememberPulseAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.40f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    return alpha
}

/** Card border that breathes: a purple→violet gradient outline with pulsing alpha. */
@Composable
fun Modifier.pulsingGlowBorder(shape: Shape, width: Dp = 1.5.dp): Modifier {
    val alpha = rememberPulseAlpha()
    return border(
        width,
        Brush.linearGradient(listOf(BrandPurple.copy(alpha = alpha), BrandPink.copy(alpha = alpha * 0.82f))),
        shape,
    )
}

/** Small status dot with a soft outer glow, optionally pulsing. */
@Composable
fun GlowDot(color: Color, pulse: Boolean = false, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val alpha = if (pulse) rememberPulseAlpha() else 1f
    Box(
        modifier
            .size(size * 2.4f)
            .drawBehind {
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.45f * alpha), Color.Transparent)))
                drawCircle(color.copy(alpha = alpha), radius = this.size.minDimension * 0.33f)
            },
    )
}

/** Pill with a glowing dot + localized status text, colored by [statusColor]. */
@Composable
fun StatusChip(status: String, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    Row(
        modifier
            .glassPanel(RoundedCornerShape(999.dp), fillAlpha = 0.06f, borderAlpha = 0.10f)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlowDot(color, pulse = status == "needs_input", size = 7.dp)
        Spacer(Modifier.width(6.dp))
        Text(statusLabel(status), color = color, style = MaterialTheme.typography.labelMedium)
    }
}

/** Shared card treatment; controls use Material 3's own shape scale. */
val CardCornerRadius = 20.dp
val CardShape = RoundedCornerShape(CardCornerRadius)

/** High-emphasis action using Material 3 sizing and the app's brand gradient. */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {

    val shape = ButtonDefaults.shape
    val fill = if (enabled) {
        BrandGradient
    } else {
        Brush.linearGradient(
            listOf(
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            ),
        )
    }
    Button(
        onClick = rememberHapticOnClick(onClick),
        enabled = enabled,
        modifier = modifier.clip(shape).background(fill, shape),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
        ),
        content = content,
    )
}
