package dev.pinkcollab.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Palette: neutral graphite surfaces with restrained violet accents ────────
val Base0 = Color(0xFF07070A)
val Base1 = Color(0xFF101014)
val BrandPurple = Color(0xFF8360DD)
val BrandPink = Color(0xFFC069C9)
val Purple400 = BrandPurple
val Purple200 = Color(0xFFCFC3F7)
val Purple700 = Color(0xFF372A57)
val Violet400 = Color(0xFF9B87F5)
val Teal300 = Color(0xFF4DE0BE)
val Amber300 = Color(0xFFF2B84B)
val Red400 = Color(0xFFFF5470)
val Gray400 = Color(0xFF85858F)
val TextHigh = Color(0xFFF4F4F5)
val TextMid = Color(0xFFB3B3BD)

val PinkCollabScheme = darkColorScheme(
    primary = Purple400,
    onPrimary = Color(0xFF160B24),
    primaryContainer = Purple700,
    onPrimaryContainer = Purple200,
    secondary = Violet400,
    onSecondary = Color(0xFF171126),
    secondaryContainer = Color(0xFF2D2540),
    onSecondaryContainer = Color(0xFFE9E3F5),
    tertiary = Teal300,
    onTertiary = Color(0xFF03211A),
    background = Base0,
    onBackground = TextHigh,
    surface = Base1,
    onSurface = TextHigh,
    surfaceVariant = Color(0xFF1A1A20),
    onSurfaceVariant = TextMid,
    surfaceContainerLowest = Base0,
    surfaceContainerLow = Color(0xFF0D0D11),
    surfaceContainer = Color(0xFF141418),
    surfaceContainerHigh = Color(0xFF1B1B21),
    surfaceContainerHighest = Color(0xFF23232A),
    outline = Color(0xFF45434C),
    outlineVariant = Color(0xFF302F36),
    error = Red400,
    onError = Color(0xFF2B040C),
    errorContainer = Color(0xFF43101E),
    onErrorContainer = Color(0xFFFFC9D3),
)

private val DefaultTypography = Typography()

/** Preserve Material 3's label rhythm with a slightly stronger optical size on dark surfaces. */
val PinkCollabTypography = Typography(
    labelLarge = DefaultTypography.labelLarge.copy(
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
    ),
)

@Composable
fun PinkCollabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PinkCollabScheme,
        typography = PinkCollabTypography,
        content = content,
    )
}

// ── Status semantics ─────────────────────────────────────────────────────────
/** Semantic task-status colors, kept separate from decorative brand accents. */
fun statusColor(status: String): Color = when (status) {
    "needs_input" -> Amber300
    "starting", "running" -> Violet400
    "completed" -> Teal300
    "failed" -> Red400
    else -> Gray400 // idle / stopped / offline / unknown
}

fun statusLabel(status: String): String = when (status) {
    "starting" -> "Starting"; "running" -> "Running"; "needs_input" -> "Needs you"; "idle" -> "Paused"
    "completed" -> "Completed"; "failed" -> "Failed"; "stopped" -> "Stopped"; "offline" -> "Offline"
    else -> status
}
