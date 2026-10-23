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
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared paper surface with explicit fill and outline colors. */
fun Modifier.paperPanel(
    shape: Shape,
    fillColor: Color = Base1,
    borderColor: Color = TextHigh.copy(alpha = 0.14f),
): Modifier = background(fillColor, shape).border(0.5.dp, borderColor, shape)

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

/** A restrained rose outline marks cards that need attention. */
@Composable
fun Modifier.pulsingGlowBorder(shape: Shape, width: Dp = 1.5.dp): Modifier {
    val alpha = rememberPulseAlpha()
    return border(
        width,
        Brush.linearGradient(listOf(AccentRose.copy(alpha = alpha), AccentRose.copy(alpha = alpha * 0.82f))),
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
fun StatusChip(status: dev.pinkcollab.data.SessionStatus, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    Row(
        modifier
            .paperPanel(RoundedCornerShape(999.dp), borderColor = TextHigh.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlowDot(color, pulse = status == dev.pinkcollab.data.SessionStatus.NeedsInput, size = 7.dp)
        Spacer(Modifier.width(6.dp))
        Text(statusLabel(status), color = color, style = MaterialTheme.typography.labelMedium)
    }
}

/** Shared card treatment; controls use Material 3's own shape scale. */
val CardCornerRadius = 16.dp
val CardShape = RoundedCornerShape(CardCornerRadius)

/** Broad, faint shadow gives paper cards depth without a dark rim. */
fun Modifier.softCardShadow(shape: Shape): Modifier = shadow(
    elevation = 12.dp,
    shape = shape,
    clip = false,
    ambientColor = Color.Black.copy(alpha = 0.18f),
    spotColor = Color.Black.copy(alpha = 0.24f),
)

/** High-emphasis charcoal action. */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = rememberHapticOnClick(onClick),
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        contentPadding = contentPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = BrandPurple,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
        ),
        content = content,
    )
}

/** Material 3 Expressive small button. compose-bom 2025.05.01 has no size tokens. */
internal val SmallButtonHeight = 40.dp
internal val SmallButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)

/** Drops the 48dp touch-target floor so a 40dp button stays 40dp. */
@Composable
internal fun SmallButtons(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp, content = content)
}
