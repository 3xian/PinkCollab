package dev.pinkcollab.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal val RetroInk = Color(0xFF151310)
internal val RetroText = Color(0xFFF2E5CA)
internal val RetroMutedText = Color(0xFFBDAE95)
internal val RetroBrass = Color(0xFFC6A46A)
internal val RetroSurfaceTop = Color(0xFF3B352D)
internal val RetroSurfaceBottom = Color(0xFF26221E)
internal val RetroInsetTop = Color(0xFF141311)
internal val RetroInsetBottom = Color(0xFF201D19)
internal val RetroAmberTop = Color(0xFFC58942)
internal val RetroAmberBottom = Color(0xFFAF7938)
internal val RetroGreenTop = Color(0xFF606A39)
internal val RetroGreenBottom = Color(0xFF384529)
internal val RetroDangerTop = Color(0xFF783C32)
internal val RetroDangerBottom = Color(0xFF48261F)
internal val RetroShape = RoundedCornerShape(3.dp)

/** Static charcoal backdrop. Texture paths are built only when the drawing size changes. */
internal fun Modifier.retroBackdrop(): Modifier = drawWithCache {
    val base = Brush.verticalGradient(
        listOf(Color(0xFF211F1B), Base0, Color(0xFF151512)), endY = size.height,
    )
    val grain = Path().apply {
        val step = 29.dp.toPx()
        var y = step / 2
        var row = 0
        while (y < size.height) {
            var x = if (row % 2 == 0) step / 3 else step * 0.8f
            while (x < size.width) {
                moveTo(x, y); lineTo(x + 2.dp.toPx(), y + 1.dp.toPx())
                x += step
            }
            row++; y += step
        }
    }
    onDrawBehind {
        drawRect(base)
        drawPath(grain, RetroBrass.copy(alpha = 0.045f), style = Stroke(0.6.dp.toPx()))
    }
}

/** Aged bronze keyline, quiet grain and a raised or recessed bevel, without moving shaders. */
internal fun Modifier.retroPanel(
    top: Color = Color.Unspecified,
    bottom: Color = Color.Unspecified,
    inset: Boolean = false,
    accented: Boolean = false,
): Modifier = drawWithCache {
    val radius = CornerRadius(3.dp.toPx())
    val depth = 3.dp.toPx()
    onDrawBehind {
        if (!inset) drawRoundRect(
            Color.Black.copy(alpha = 0.42f), topLeft = Offset(0f, depth), cornerRadius = radius,
        )
    }
}.clip(RetroShape).drawWithCache {
    val resolvedTop = if (top == Color.Unspecified) { if (inset) RetroInsetTop else RetroSurfaceTop } else top
    val resolvedBottom = if (bottom == Color.Unspecified) { if (inset) RetroInsetBottom else RetroSurfaceBottom } else bottom
    val fill = Brush.verticalGradient(listOf(resolvedTop, resolvedBottom), endY = size.height)
    val pixel = 1.dp.toPx()
    val radius = CornerRadius(3.dp.toPx())
    val keyline = if (accented) RetroBrass else RetroBrass.copy(alpha = 0.30f)
    val bevelTop = if (inset) Color.Black.copy(alpha = 0.65f) else RetroText.copy(alpha = 0.12f)
    val bevelBottom = if (inset) RetroBrass.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.55f)
    val grain = Path().apply {
        var y = 8.dp.toPx()
        while (y < size.height) {
            moveTo(0f, y); lineTo(size.width, y)
            y += 13.dp.toPx()
        }
    }
    onDrawBehind {
        drawRoundRect(fill, cornerRadius = radius)
        drawPath(grain, RetroText.copy(alpha = 0.018f), style = Stroke(pixel / 2))
        drawRoundRect(
            keyline, topLeft = Offset(pixel / 2, pixel / 2),
            size = Size((size.width - pixel).coerceAtLeast(0f), (size.height - pixel).coerceAtLeast(0f)),
            cornerRadius = radius, style = Stroke(pixel),
        )
        drawLine(bevelTop, Offset(3 * pixel, 2 * pixel), Offset(size.width - 3 * pixel, 2 * pixel), pixel)
        drawLine(bevelBottom, Offset(3 * pixel, size.height - 2 * pixel),
            Offset(size.width - 3 * pixel, size.height - 2 * pixel), pixel)
    }
}
