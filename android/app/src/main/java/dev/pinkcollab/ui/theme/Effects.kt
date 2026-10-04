package dev.pinkcollab.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val BrandGradient: Brush = Brush.linearGradient(listOf(BrandBrass, BrandBronze))

/** Paint icon/vector content with the same antique brass gradient used by text. */
fun Modifier.brandGradientMask(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            onDrawWithContent {
                drawContent()
                drawRect(brush = BrandGradient, blendMode = BlendMode.SrcIn)
            }
        }

/** Bronze edge treatment for subdued, dark controls. */
private fun edgeBrush(alpha: Float): Brush =
    Brush.linearGradient(listOf(BrandBrass.copy(alpha = alpha), BrandBronze.copy(alpha = alpha)))

/** Static bronze-tinted framing for compact chips and custom-shaped activity surfaces. */
fun Modifier.framedPanel(
    shape: Shape,
    fillAlpha: Float = 0.07f,
    borderAlpha: Float = 0.14f,
    borderBrush: Brush? = null,
): Modifier = background(
    Brush.linearGradient(listOf(BrandBrass.copy(alpha = fillAlpha), BrandBronze.copy(alpha = fillAlpha * 0.72f))),
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

/** Localized state emphasis, never an animated full-screen background. */
fun Modifier.stateAccentBorder(shape: Shape, width: Dp = 1.5.dp): Modifier =
    border(width, RetroBrass.copy(alpha = 0.70f), shape)

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
fun StatusChip(status: dev.pinkcollab.data.SessionStatus, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    Row(
        modifier
            .framedPanel(RoundedCornerShape(3.dp), fillAlpha = 0.06f, borderAlpha = 0.10f)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlowDot(color, pulse = status == dev.pinkcollab.data.SessionStatus.NeedsInput, size = 7.dp)
        Spacer(Modifier.width(6.dp))
        Text(statusLabel(status), color = color, style = MaterialTheme.typography.labelMedium)
    }
}

/** Shallow bevels and small corners keep panels aligned with the antique component system. */
val CardCornerRadius = 4.dp
val CardShape = RoundedCornerShape(CardCornerRadius)

/** Raised amber action with legible dark text and the usual Material touch target. */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {

    val shape = RetroShape
    Button(
        onClick = rememberHapticOnClick(onClick),
        enabled = enabled,
        modifier = modifier.retroPanel(
            if (enabled) RetroAmberTop else RetroSurfaceTop,
            if (enabled) RetroAmberBottom else RetroSurfaceBottom,
            accented = enabled,
        ),
        contentPadding = contentPadding,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            contentColor = RetroInk,
            disabledContentColor = RetroMutedText,
        ),
        content = content,
    )
}

/** Compact actions keep the platform's minimum touch-target height. */
internal val SmallButtonHeight = 48.dp
internal val SmallButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
