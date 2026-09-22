package dev.pinkcollab.ui.theme

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

val BrandGradient: Brush = Brush.linearGradient(listOf(BrandPurple, BrandPink))

/** Paint icon/vector content with the same purple-to-pink brand gradient used by text. */
fun Modifier.brandGradientMask(): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            onDrawWithContent {
                drawContent()
                drawRect(brush = BrandGradient, blendMode = BlendMode.SrcIn)
            }
        }

private const val AnimatedNoiseShader = """
    uniform float2 resolution;
    uniform float phase;
    layout(color) uniform half4 tint;

    float hash(float2 p) {
        p = fract(p * float2(123.34, 456.21));
        p += dot(p, p + 45.32);
        return fract(p.x * p.y);
    }

    float valueNoise(float2 p) {
        float2 cell = floor(p);
        float2 local = fract(p);
        local = local * local * (3.0 - 2.0 * local);
        return mix(
            mix(hash(cell), hash(cell + float2(1.0, 0.0)), local.x),
            mix(hash(cell + float2(0.0, 1.0)), hash(cell + float2(1.0, 1.0)), local.x),
            local.y
        );
    }

    float fbm(float2 p) {
        float value = valueNoise(p) * 0.52;
        p = p * 2.03 + 11.7;
        value += valueNoise(p) * 0.28;
        p = p * 2.01 + 7.3;
        value += valueNoise(p) * 0.14;
        p = p * 2.04 + 5.1;
        return value + valueNoise(p) * 0.06;
    }

    half4 main(float2 fragCoord) {
        float2 uv = fragCoord / resolution;
        float aspect = resolution.x / resolution.y;
        float horizontalScale = mix(1.0, aspect, 0.45);
        float2 point = float2(uv.x * horizontalScale, uv.y);
        float2 orbit = float2(cos(phase), sin(phase));
        float2 warp = float2(
            fbm(point * 1.2 + orbit * 0.32),
            fbm(point * 1.2 + float2(4.8, 1.9) - orbit * 0.28)
        );
        float field = fbm(point * 1.7 + warp * 1.7 + orbit * 0.5);
        float detail = valueNoise(point * 4.2 - warp + orbit.yx * 0.8);
        float opacity = (0.018 + smoothstep(0.30, 0.76, field * 0.88 + detail * 0.12) * 0.12) * tint.a;
        return half4(tint.rgb, half(opacity));
    }
"""

/**
 * Slowly warps status-colored fractal noise behind active content. API 33+ uses
 * AGSL; older devices retain a low-cost two-bloom approximation.
 */
@Composable
fun Modifier.animatedNoiseGradient(
    active: Boolean,
    tint: Color,
): Modifier {
    if (!active) return this
    val transition = rememberInfiniteTransition(label = "animatedNoiseGradient")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (PI * 2.0).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8_000, easing = LinearEasing),
        ),
        label = "animatedNoiseGradientPhase",
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return drawWithCache {
            val shader = RuntimeShader(AnimatedNoiseShader)
            shader.setFloatUniform("resolution", size.width, size.height)
            shader.setColorUniform(
                "tint",
                android.graphics.Color.valueOf(tint.red, tint.green, tint.blue, tint.alpha),
            )
            val brush = ShaderBrush(shader)
            onDrawBehind {
                shader.setFloatUniform("phase", phase)
                drawRect(brush)
            }
        }
    }
    return drawWithCache {
        val largeRadius = size.width * 0.58f
        val smallRadius = size.width * 0.34f
        val largeBloom = Brush.radialGradient(
            colors = listOf(tint.copy(alpha = 0.15f), Color.Transparent),
            center = Offset(largeRadius, largeRadius),
            radius = largeRadius,
        )
        val smallBloom = Brush.radialGradient(
            colors = listOf(tint.copy(alpha = 0.11f), Color.Transparent),
            center = Offset(smallRadius, smallRadius),
            radius = smallRadius,
        )
        onDrawBehind {
            val largeX = (cos(phase) * 0.5f + 0.5f) * size.width
            val largeY = (sin(phase) * 0.24f + 0.5f) * size.height
            translate(left = largeX - largeRadius, top = largeY - largeRadius) {
                drawRect(largeBloom, size = Size(largeRadius * 2f, largeRadius * 2f))
            }
            val smallX = (sin(phase) * 0.5f + 0.5f) * size.width
            val smallY = (cos(phase) * -0.28f + 0.5f) * size.height
            translate(left = smallX - smallRadius, top = smallY - smallRadius) {
                drawRect(smallBloom, size = Size(smallRadius * 2f, smallRadius * 2f))
            }
        }
    }
}

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
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
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
        contentPadding = contentPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
        ),
        content = content,
    )
}
