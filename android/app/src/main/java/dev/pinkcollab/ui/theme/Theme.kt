package dev.pinkcollab.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Palette: black base, electric-purple accents ──────────────────────────────
val Base0 = Color(0xFF050408) // app background, near-black with a violet cast
val Base1 = Color(0xFF0B0811) // lifted surface
val Purple400 = Color(0xFF9B6CFF) // primary electric purple
val Purple200 = Color(0xFFD8C7FF)
val Purple700 = Color(0xFF4B287F)
val Violet400 = Color(0xFF7C4DFF)
val Teal300 = Color(0xFF4DE0BE)
val Red400 = Color(0xFFFF5470)
val Gray400 = Color(0xFF8C849B)
val TextHigh = Color(0xFFF4F0FA)
val TextMid = Color(0xFFB1A7C0)

val PinkCollabScheme = darkColorScheme(
    primary = Purple400,
    onPrimary = Color(0xFF10051F),
    primaryContainer = Purple700,
    onPrimaryContainer = Purple200,
    secondary = Violet400,
    onSecondary = Color(0xFF14021F),
    secondaryContainer = Color(0xFF2E1A45),
    onSecondaryContainer = Color(0xFFE2D2FF),
    tertiary = Teal300,
    onTertiary = Color(0xFF03211A),
    background = Base0,
    onBackground = TextHigh,
    surface = Base1,
    onSurface = TextHigh,
    surfaceVariant = Color(0xFF181321),
    onSurfaceVariant = TextMid,
    surfaceContainerLowest = Base0,
    surfaceContainerLow = Color(0xFF0A0710),
    surfaceContainer = Color(0xFF100B18),
    surfaceContainerHigh = Color(0xFF171020),
    surfaceContainerHighest = Color(0xFF20162D),
    outline = Color(0xFF493F58),
    outlineVariant = Color(0xFF30293A),
    error = Red400,
    onError = Color(0xFF2B040C),
    errorContainer = Color(0xFF43101E),
    onErrorContainer = Color(0xFFFFC9D3),
)

@Composable
fun PinkCollabTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PinkCollabScheme, content = content)
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
