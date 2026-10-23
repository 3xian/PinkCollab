package dev.pinkcollab.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
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
// Paper surfaces, charcoal controls, and a restrained rose accent.
val Base0 = Color(0xFFEDEDEB)
val Base1 = Color(0xFFFAFAF8)
val HeaderBackground = Color(0xFF343433)
val HeaderInk = Color(0xFFF5F5F2)
val AccentRose = Color(0xFFA33750)
// Shared legacy token names also style markdown and runtime controls.
val BrandPurple = Color(0xFF242424)
val BrandPink = Color(0xFF51514F)
val Purple400 = BrandPurple
val Purple200 = Color(0xFF464644)
val Purple700 = Color(0xFFE0E0DD)
val Violet400 = Color(0xFF585856)
val Teal300 = Color(0xFF42685A)
val Amber300 = Color(0xFF8A5D28)
val Red400 = AccentRose
val Gray400 = Color(0xFF747471)
val TextHigh = Color(0xFF242423)
val TextMid = Color(0xFF60605D)

val PinkCollabScheme = lightColorScheme(
    primary = Purple400,
    onPrimary = Color.White,
    primaryContainer = Purple700,
    onPrimaryContainer = Purple200,
    secondary = Violet400,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E4E1),
    onSecondaryContainer = TextHigh,
    tertiary = Teal300,
    onTertiary = Color.White,
    background = Base0,
    onBackground = TextHigh,
    surface = Base1,
    onSurface = TextHigh,
    surfaceVariant = Color(0xFFE3E3E0),
    onSurfaceVariant = TextMid,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F6F3),
    surfaceContainer = Color(0xFFF1F1EE),
    surfaceContainerHigh = Color(0xFFE9E9E6),
    surfaceContainerHighest = Color(0xFFDEDEDA),
    surfaceTint = Color.Transparent,
    inverseSurface = HeaderBackground,
    inverseOnSurface = HeaderInk,
    inversePrimary = Color(0xFFDADAD6),
    outline = Color(0xFF858580),
    outlineVariant = Color(0xFFD4D4CF),
    error = Red400,
    onError = Color.White,
    errorContainer = Color(0xFFF3E3E6),
    onErrorContainer = Color(0xFF75253A),
)

private val MapleMono = FontFamily(
    Font(R.font.maple_mono_cn_regular, FontWeight.Normal),
)

private fun TextStyle.sessionFont(sizeSp: Float = fontSize.value) = copy(fontFamily = MapleMono, fontSize = sizeSp.sp)

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
    bodyLarge = bodyLarge.sessionFont(12f),
    bodyMedium = bodyMedium.sessionFont(12f),
    bodySmall = bodySmall.sessionFont(12f),
    labelLarge = labelLarge.sessionFont(),
    labelMedium = labelMedium.sessionFont(),
    labelSmall = labelSmall.sessionFont(),
)

/** Compact type scale; Android's user font scale remains authoritative. */
val PinkCollabTypography = Typography(
    displayLarge = TextStyle(fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.Light),
    displayMedium = TextStyle(fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Light),
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Light),
    headlineLarge = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Light),
    headlineMedium = TextStyle(fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.Normal),
    headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    bodyMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Normal),
)

internal val DialogHeadlineStyle = PinkCollabTypography.headlineSmall
internal val SheetTitleStyle = PinkCollabTypography.titleLarge

/** Message list only. Heavier weights are synthesized from the bundled regular face. */
internal val SessionTypography = PinkCollabTypography.sessionFont()

@Composable
fun PinkCollabTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PinkCollabScheme,
        typography = PinkCollabTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(18.dp),
            extraLarge = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}

// ── Status semantics ─────────────────────────────────────────────────────────
/** Semantic session-status colors, kept separate from decorative brand accents. */
fun statusColor(status: SessionStatus): Color = when (status) {
    SessionStatus.NeedsInput -> Amber300
    SessionStatus.Starting, SessionStatus.Running, SessionStatus.Stopping -> Violet400
    SessionStatus.Idle -> Gray400
}

fun statusLabel(status: SessionStatus): String = when (status) {
    SessionStatus.Starting -> "Starting"
    SessionStatus.Stopping -> "Stopping"
    SessionStatus.Running -> "Running"
    SessionStatus.NeedsInput -> "Needs you"
    SessionStatus.Idle -> "Paused"
}
