package dev.pinkcollab.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Palette: black base, neon pink accents ────────────────────────────────────
val Base0 = Color(0xFF07050A) // app background, near-black with a violet cast
val Base1 = Color(0xFF0D0912) // lifted surface
val Pink400 = Color(0xFFFF4D8D) // primary neon pink
val Pink200 = Color(0xFFFFB0CD)
val Pink700 = Color(0xFF8C2350)
val Violet400 = Color(0xFFA06BFF)
val Teal300 = Color(0xFF4DE0BE)
val Red400 = Color(0xFFFF5470)
val Gray400 = Color(0xFF8E7F8C)
val TextHigh = Color(0xFFF6EEF4)
val TextMid = Color(0xFFB5A4B4)

val PinkCollabScheme = darkColorScheme(
    primary = Pink400,
    onPrimary = Color(0xFF1A020C),
    primaryContainer = Pink700,
    onPrimaryContainer = Pink200,
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
    surfaceVariant = Color(0xFF1C1420),
    onSurfaceVariant = TextMid,
    surfaceContainerLowest = Base0,
    surfaceContainerLow = Color(0xFF0C0810),
    surfaceContainer = Color(0xFF120C16),
    surfaceContainerHigh = Color(0xFF191020),
    surfaceContainerHighest = Color(0xFF211529),
    outline = Color(0xFF4A3D47),
    outlineVariant = Color(0xFF312830),
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
    "needs_input" -> Pink400 // the one state that must shout for attention
    "starting", "running" -> Violet400
    "completed" -> Teal300
    "failed" -> Red400
    else -> Gray400 // idle / stopped / offline / unknown
}

fun statusLabel(status: String): String = when (status) {
    "starting" -> "启动中"; "running" -> "进行中"; "needs_input" -> "等待你回复"; "idle" -> "已暂停"
    "completed" -> "已完成"; "failed" -> "失败"; "stopped" -> "已停止"; "offline" -> "离线"
    else -> status
}
