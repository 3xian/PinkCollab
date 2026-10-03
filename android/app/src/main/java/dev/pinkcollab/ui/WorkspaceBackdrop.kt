package dev.pinkcollab.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Matrix
import java.util.Locale

internal fun workspaceIconColor(os: String): Color = when (os.lowercase(Locale.ROOT)) {
    "windows" -> Color(0xFF69B9FF)
    "macos", "darwin" -> Color(0xFFDCE5F3)
    "linux" -> Color(0xFFFFB763)
    else -> Color(0xFFA78BFA)
}

/** Scalable wallpaper art, kept faint so host details and workspace rows stay readable. */
internal fun Modifier.workspaceBackdrop(os: String): Modifier = drawWithCache {
    val width = size.width
    val height = size.height
    val platform = os.lowercase(Locale.ROOT)
    val accent = when (platform) {
        "windows" -> Color(0xFF38A5FF)
        "macos", "darwin" -> Color(0xFFB9C9EE)
        "linux" -> Color(0xFFE99845)
        else -> Color(0xFFA78BFA)
    }
    val glow = Brush.radialGradient(
        listOf(accent.copy(alpha = 0.15f), Color.Transparent),
        center = Offset(width * 0.88f, height * 0.24f),
        radius = width * 0.9f,
    )
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
            listOf(Color(0xFFE5EBF5).copy(alpha = 0.19f), accent.copy(alpha = 0.08f))
        } else {
            listOf(accent.copy(alpha = 0.13f), Color(0xFF7C63DB).copy(alpha = 0.035f))
        },
        start = Offset(width, 0f),
        end = Offset(width * 0.4f, height * 0.7f),
    )
    // Fade the artwork before the directory list so long cards retain a quiet background.
    val shade = Brush.verticalGradient(
        listOf(Color.Transparent, Color(0xFF13111B).copy(alpha = 0.3f)),
        endY = height * 0.65f,
    )
    onDrawBehind {
        drawRect(glow, size = Size(width, height))
        drawPath(artwork, artBrush)
        drawRect(shade)
    }
}
