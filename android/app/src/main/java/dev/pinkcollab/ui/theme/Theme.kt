package dev.pinkcollab.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Palette: black base, violet→orchid brand accents ─────────────────────────
val Base0 = Color(0xFF050408)
val Base1 = Color(0xFF0C0910)
val BrandPurple = Color(0xFF8360DD)
val BrandPink = Color(0xFFC069C9)
val Purple400 = BrandPurple
val Purple200 = Color(0xFFE1CFEC)
val Purple700 = Color(0xFF3C2A64)
val Violet400 = BrandPink
val Teal300 = Color(0xFF4DE0BE)
val Red400 = Color(0xFFFF5470)
val Gray400 = Color(0xFFA89EAD)
val TextHigh = Color(0xFFF7F1F8)
val TextMid = Color(0xFFC9BDCC)

val PinkCollabScheme = darkColorScheme(
    primary = Purple400,
    onPrimary = Color(0xFF160B24),
    primaryContainer = Purple700,
    onPrimaryContainer = Purple200,
    secondary = Violet400,
    onSecondary = Color(0xFF260A21),
    secondaryContainer = Color(0xFF462441),
    onSecondaryContainer = Color(0xFFF3D8F0),
    tertiary = Teal300,
    onTertiary = Color(0xFF03211A),
    background = Base0,
    onBackground = TextHigh,
    surface = Base1,
    onSurface = TextHigh,
    surfaceVariant = Color(0xFF1A151E),
    onSurfaceVariant = TextMid,
    surfaceContainerLowest = Base0,
    surfaceContainerLow = Color(0xFF0B080E),
    surfaceContainer = Color(0xFF120D16),
    surfaceContainerHigh = Color(0xFF1A121F),
    surfaceContainerHighest = Color(0xFF25182A),
    outline = Color(0xFF514457),
    outlineVariant = Color(0xFF352B39),
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
/** Neon accent color for a task status, per Chinese-market dark-UI conventions. */
fun statusColor(status: String): Color = when (status) {
    "needs_input" -> Purple400 // the one state that must shout for attention
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
