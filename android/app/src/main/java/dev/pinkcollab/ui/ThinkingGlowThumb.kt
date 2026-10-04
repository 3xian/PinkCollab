package dev.pinkcollab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.pinkcollab.ui.theme.RetroAmberBottom
import dev.pinkcollab.ui.theme.RetroAmberTop
import dev.pinkcollab.ui.theme.RetroBrass
import dev.pinkcollab.ui.theme.RetroInk

/** A static brass dial preserves the slider center without decorative glow. */
@Composable
internal fun ThinkingGlowThumb(enabled: Boolean, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension * 0.34f
        drawCircle(RetroInk, radius + 2f, center + Offset(0f, 2f))
        drawCircle(
            brush = Brush.verticalGradient(
                if (enabled) listOf(RetroAmberTop, RetroAmberBottom) else listOf(muted, muted),
            ),
            radius = radius,
            center = center,
        )
        drawCircle(if (enabled) RetroBrass else muted, radius, center, style = Stroke(1.5f))
        drawLine(
            color = RetroInk,
            start = center - Offset(0f, radius * 0.45f),
            end = center + Offset(0f, radius * 0.45f),
            strokeWidth = 2f,
        )
    }
}
