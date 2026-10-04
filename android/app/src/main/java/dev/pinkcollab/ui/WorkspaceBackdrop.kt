package dev.pinkcollab.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Matrix
import dev.pinkcollab.ui.theme.RetroBrass
import dev.pinkcollab.ui.theme.RetroInk
import dev.pinkcollab.ui.theme.RetroMutedText
import java.util.Locale

internal fun workspaceIconColor(os: String): Color = when (os.lowercase(Locale.ROOT)) {
    "windows" -> RetroMutedText
    "macos", "darwin" -> Color(0xFFD8C9AE)
    "linux" -> RetroBrass
    else -> RetroBrass
}

/** Scalable wallpaper art, kept faint so host details and workspace rows stay readable. */
internal fun Modifier.workspaceBackdrop(os: String): Modifier = drawWithCache {
    val width = size.width
    val height = size.height
    val platform = os.lowercase(Locale.ROOT)
    val accent = workspaceIconColor(platform)
    val artwork = when (platform) {
        "windows" -> {
            val pane = width * 0.16f
            val gap = width * 0.012f
            val left = width * 0.62f
            val top = -pane * 0.12f
            Path().apply {
                for (row in 0..1) for (column in 0..1) {
                    val x = left + column * (pane + gap)
                    val y = top + row * (pane + gap)
                    moveTo(x, y)
                    lineTo(x + pane, y)
                    lineTo(x + pane, y + pane)
                    lineTo(x, y + pane)
                    close()
                }
            }
        }
        "macos", "darwin" -> Path().apply {
            // A bitten apple and separate leaf make the platform recognizable at a glance.
            // Use one uniform scale so its silhouette survives cards of different heights.
            moveTo(52f, 28f)
            cubicTo(41f, 28f, 34f, 21f, 23f, 28f)
            cubicTo(5f, 39f, 12f, 65f, 24f, 85f)
            cubicTo(34f, 101f, 40f, 88f, 51f, 89f)
            cubicTo(63f, 88f, 68f, 100f, 79f, 85f)
            cubicTo(84f, 78f, 88f, 70f, 90f, 64f)
            cubicTo(69f, 59f, 67f, 40f, 85f, 31f)
            cubicTo(76f, 20f, 65f, 23f, 52f, 28f)
            close()
            moveTo(51f, 23f)
            cubicTo(50f, 11f, 61f, 2f, 72f, 1f)
            cubicTo(73f, 12f, 63f, 24f, 51f, 23f)
            close()
            transform(Matrix().apply {
                translate(width * 0.62f, width * 0.015f)
                scale(width * 0.0035f, width * 0.0035f)
            })
        }
        "linux" -> Path().apply {
            moveTo(width * 0.38f, height * 0.58f)
            lineTo(width * 0.72f, height * 0.04f)
            lineTo(width * 0.85f, height * 0.28f)
            lineTo(width, height * 0.12f)
            lineTo(width, height * 0.72f)
            close()
            moveTo(width * 0.72f, height * 0.04f)
            lineTo(width * 0.66f, height * 0.43f)
            lineTo(width * 0.85f, height * 0.28f)
            close()
        }
        else -> Path()
    }
    val artBrush = Brush.linearGradient(
        if (platform == "macos" || platform == "darwin") {
            listOf(RetroMutedText.copy(alpha = 0.12f), accent.copy(alpha = 0.05f))
        } else {
            listOf(accent.copy(alpha = 0.10f), RetroBrass.copy(alpha = 0.035f))
        },
        start = Offset(width, 0f),
        end = Offset(width * 0.4f, height * 0.7f),
    )
    // Fade the artwork before the directory list so long cards retain a quiet background.
    val shade = Brush.verticalGradient(
        listOf(Color.Transparent, RetroInk.copy(alpha = 0.3f)),
        endY = height * 0.65f,
    )
    onDrawBehind {
        drawPath(artwork, artBrush)
        drawRect(shade)
    }
}
