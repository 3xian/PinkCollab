package dev.pinkcollab.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import dev.pinkcollab.R
import dev.pinkcollab.data.SessionStatus
// ── Palette: aged charcoal, bronze and legible parchment text ─────────────────
val Base0 = Color(0xFF191815)
val Base1 = Color(0xFF2C2822)
val BrandBrass = Color(0xFFC6A46A)
val BrandBronze = Color(0xFF997442)
val BrassLight = Color(0xFFE4CCA1)
val BrassDark = Color(0xFF53422B)
val ActivityAmber = Color(0xFFDBB36E)
val SuccessOlive = Color(0xFFA8BB79)
val WarningAmber = Color(0xFFE8B16B)
val ErrorRed = Color(0xFFE39B86)
val MutedText = Color(0xFFAD9E88)
val TextHigh = Color(0xFFF2E5CA)
val TextMid = Color(0xFFBDAE95)

val PinkCollabScheme = darkColorScheme(
    primary = BrandBrass,
    onPrimary = Color(0xFF241B10),
    primaryContainer = BrassDark,
    onPrimaryContainer = BrassLight,
    secondary = ActivityAmber,
    onSecondary = Color(0xFF291E10),
    secondaryContainer = Color(0xFF4B3821),
    onSecondaryContainer = Color(0xFFF0D3A3),
    tertiary = SuccessOlive,
    onTertiary = Color(0xFF19200F),
    background = Base0,
    onBackground = TextHigh,
    surface = Base1,
    onSurface = TextHigh,
    surfaceVariant = Color(0xFF3A342C),
    onSurfaceVariant = TextMid,
    surfaceContainerLowest = Base0,
    surfaceContainerLow = Color(0xFF24211D),
    surfaceContainer = Base1,
    surfaceContainerHigh = Color(0xFF39332B),
    surfaceContainerHighest = Color(0xFF453D32),
    inverseSurface = Color(0xFF453D32),
    inverseOnSurface = TextHigh,
    inversePrimary = BrassLight,
    outline = Color(0xFF8D795A),
    outlineVariant = Color(0xFF5A4D3A),
    error = ErrorRed,
    onError = Color(0xFF30130E),
    errorContainer = Color(0xFF542C24),
    onErrorContainer = Color(0xFFF8D7C7),
)

private val MapleMono = FontFamily(
    Font(R.font.maple_mono_cn_regular, FontWeight.Normal),
)

private fun TextStyle.sessionFont() = copy(fontFamily = MapleMono, fontSize = (fontSize.value - 0.5f).sp)

private fun Typography.sessionFont() = copy(
    displayLarge = displayLarge.sessionFont(),
    displayMedium = displayMedium.sessionFont(),
    displaySmall = displaySmall.sessionFont(),
    headlineLarge = headlineLarge.sessionFont(),
    headlineMedium = headlineMedium.sessionFont(),
    headlineSmall = headlineSmall.sessionFont(),
    titleLarge = titleLarge.sessionFont(),
    titleMedium = titleMedium.sessionFont(),
    titleSmall = titleSmall.sessionFont(),
    bodyLarge = bodyLarge.sessionFont(),
    bodyMedium = bodyMedium.sessionFont(),
    bodySmall = bodySmall.sessionFont(),
    labelLarge = labelLarge.sessionFont(),
    labelMedium = labelMedium.sessionFont(),
    labelSmall = labelSmall.sessionFont(),
)

private val DefaultTypography = Typography()

/** Preserve Material 3's label rhythm with a slightly stronger optical size on dark surfaces. */
val PinkCollabTypography = Typography(
    labelLarge = DefaultTypography.labelLarge.copy(
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
    ),
)

/** Message list only. Heavier weights are synthesized from the bundled regular face. */
internal val SessionTypography = PinkCollabTypography.sessionFont()

private val PinkCollabShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

@Composable
fun PinkCollabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PinkCollabScheme,
        typography = PinkCollabTypography,
        shapes = PinkCollabShapes,
        content = content,
    )
}

// ── Status semantics ─────────────────────────────────────────────────────────
/** Semantic session-status colors, kept separate from decorative brand accents. */
fun statusColor(status: SessionStatus): Color = when (status) {
    SessionStatus.NeedsInput -> WarningAmber
    SessionStatus.Starting, SessionStatus.Running, SessionStatus.Stopping -> ActivityAmber
    SessionStatus.Idle -> MutedText
}

fun statusLabel(status: SessionStatus): String = when (status) {
    SessionStatus.Starting -> "Starting"
    SessionStatus.Stopping -> "Stopping"
    SessionStatus.Running -> "Running"
    SessionStatus.NeedsInput -> "Needs you"
    SessionStatus.Idle -> "Paused"
}
