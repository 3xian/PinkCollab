package dev.pinkcollab.ui

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.DrawScope
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
        listOf(accent.copy(alpha = if (platform == "linux") 0.08f else 0.15f), Color.Transparent),
        center = Offset(width * 0.88f, height * 0.24f),
        radius = width * 0.9f,
    )
    val drawArtwork = if (platform == "linux") {
        linuxArtwork(accent)
    } else {
        platformArtwork(platform, accent)
    }
    // Fade the artwork before the directory list so long cards retain a quiet background.
    val shade = Brush.verticalGradient(
        listOf(Color.Transparent, Color(0xFF13111B).copy(alpha = 0.3f)),
        endY = height * 0.65f,
    )
    onDrawBehind {
        drawRect(glow, size = Size(width, height))
        drawArtwork()
        drawRect(shade)
    }
}

private fun CacheDrawScope.platformArtwork(platform: String, accent: Color): DrawScope.() -> Unit {
    val width = size.width
    val height = size.height
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
    return { drawPath(artwork, artBrush) }
}

private fun CacheDrawScope.linuxArtwork(accent: Color): DrawScope.() -> Unit {
    val width = size.width
    val linuxWidth = width * 0.273f
    val linuxScale = linuxWidth / 220f
    // Match the apple artwork's horizontal center rather than its right edge.
    val linuxLeft = width * 0.80f - linuxWidth / 2f
    val linuxTop = width * 0.015f
    val artwork = Path().apply {
        // Open face silhouette; the card shows through the face.
        moveTo(7f, 134f)
        cubicTo(2f, 134f, 0f, 131f, 0f, 126f)
        cubicTo(-9f, 58f, 43f, 0f, 110f, 0f)
        cubicTo(177f, 0f, 229f, 58f, 220f, 126f)
        cubicTo(220f, 131f, 218f, 134f, 213f, 134f)
        lineTo(195f, 134f)
        cubicTo(189f, 134f, 188f, 131f, 190f, 124f)
        cubicTo(198f, 86f, 178f, 64f, 153f, 64f)
        cubicTo(133f, 64f, 126f, 77f, 110f, 77f)
        cubicTo(94f, 77f, 87f, 64f, 67f, 64f)
        cubicTo(42f, 64f, 22f, 86f, 30f, 124f)
        cubicTo(32f, 131f, 31f, 134f, 25f, 134f)
        close()
        addOval(Rect(57f, 82f, 77f, 102f))
        addOval(Rect(142f, 82f, 162f, 102f))
        transform(Matrix().apply {
            translate(linuxLeft, linuxTop)
            scale(linuxScale, linuxScale)
        })
    }
    val ink = Brush.linearGradient(
        listOf(Color(0xFFB9C9D8).copy(alpha = 0.10f), Color(0xFFB9C9D8).copy(alpha = 0.04f)),
        start = Offset(width, 0f),
        end = Offset(width * 0.6f, width * 0.38f),
    )
    val beak = Path().apply {
        moveTo(96f, 115f)
        lineTo(124f, 115f)
        cubicTo(127f, 115f, 128f, 117f, 126f, 120f)
        lineTo(114f, 132f)
        cubicTo(112f, 135f, 108f, 135f, 106f, 132f)
        lineTo(94f, 120f)
        cubicTo(92f, 117f, 93f, 115f, 96f, 115f)
        close()
        transform(Matrix().apply {
            translate(linuxLeft, linuxTop)
            scale(linuxScale, linuxScale)
        })
    }
    // Size the word from card width so a tall card does not stretch it.
    val word = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = width * 0.15f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        textSize *= linuxWidth / measureText("linux")
        shader = LinearGradient(
            width,
            0f,
            width * 0.6f,
            width * 0.38f,
            Color(0xFFB9C9D8).copy(alpha = 0.10f).toArgb(),
            Color(0xFFB9C9D8).copy(alpha = 0.04f).toArgb(),
            Shader.TileMode.CLAMP,
        )
    }
    val beakColor = accent.copy(alpha = 0.12f)
    return {
        drawPath(artwork, ink)
        drawPath(beak, beakColor)
        drawContext.canvas.nativeCanvas.drawText("linux", linuxLeft, linuxTop + 228f * linuxScale, word)
    }
}
