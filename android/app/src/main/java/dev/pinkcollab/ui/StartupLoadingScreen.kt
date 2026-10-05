package dev.pinkcollab.ui

import android.graphics.Movie
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import dev.pinkcollab.R
import dev.pinkcollab.data.AppState
import dev.pinkcollab.data.TaskListLoadState
import dev.pinkcollab.ui.theme.Base0
import dev.pinkcollab.ui.theme.BrandPink
import dev.pinkcollab.ui.theme.BrandPurple
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlin.math.PI
import kotlin.math.sin

internal suspend fun awaitStartupReadiness(
    appStates: StateFlow<AppState>,
) {
    appStates.first { it.taskListLoadState != TaskListLoadState.Loading }
}

@Composable
internal fun StartupLoadingScreen(app: NavigationState) {
    val titleText = remember {
        buildAnnotatedString {
            withStyle(SpanStyle(color = BrandPurple)) { append("Pink") }
            append("Collab")
        }
    }
    Layout(
        modifier = Modifier.fillMaxSize().background(Base0),
        content = {
            Text(
                titleText,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            AnimatedLoadingLogo()
            StartupProgress(app, Modifier.navigationBarsPadding().padding(bottom = 64.dp))
        },
    ) { measurables, constraints ->
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val title = measurables[0].measure(childConstraints)
        val logo = measurables[1].measure(childConstraints)
        val progress = measurables[2].measure(childConstraints)
        val logoTop = (constraints.maxHeight - logo.height) / 2
        layout(constraints.maxWidth, constraints.maxHeight) {
            title.placeRelative((constraints.maxWidth - title.width) / 2,
                (logoTop - 20.dp.roundToPx() - title.height).coerceAtLeast(0))
            logo.placeRelative((constraints.maxWidth - logo.width) / 2, logoTop)
            progress.placeRelative((constraints.maxWidth - progress.width) / 2,
                constraints.maxHeight - progress.height)
        }
    }
}

@Composable
internal fun AnimatedLoadingLogo(logoSize: Dp = 248.dp, animateGlow: Boolean = true, glowScale: Float = 1f) {
    val context = LocalContext.current
    val movie = remember(context) {
        context.resources.openRawResource(R.raw.login_logo).use(Movie::decodeStream)
    }
    val frameDuration = movie?.duration()?.takeIf { it > 0 } ?: 1920
    val motion = rememberInfiniteTransition(label = "loadingCharacter")
    val frameProgress = motion.animateFloat(0f, 1f,
        infiniteRepeatable(tween(frameDuration, easing = LinearEasing)), label = "gifFrame")
    val glowProgress = if (animateGlow) {
        motion.animateFloat(0f, 1f,
            infiniteRepeatable(tween(2800, easing = LinearEasing)), label = "glow")
    } else null

    Box(
        modifier = Modifier.size(logoSize),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val center = Offset(size.width * 0.51f, size.height * 0.5f)
                    val radius = size.width * 0.43f * glowScale
                    val gradient = Brush.linearGradient(
                        colors = listOf(BrandPurple, BrandPink),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    )
                    val fade = Brush.radialGradient(
                        0f to Color.White,
                        0.4f to Color.White.copy(alpha = 0.78f),
                        1f to Color.Transparent,
                        center = center,
                        radius = radius,
                    )
                    onDrawBehind {
                        val phase = glowProgress?.value ?: 0.25f
                        val wave = sin(PI * phase).toFloat()
                        val breath = wave * wave
                        val scale = 0.88f + 0.12f * breath
                        val opacity = 0.62f * (0.58f + 0.42f * breath)
                        withTransform({ scale(scale, scale, center) }) {
                            drawCircle(gradient, radius, center, alpha = opacity)
                            drawCircle(fade, radius, center, blendMode = BlendMode.DstIn)
                        }
                    }
                },
        )
        if (movie != null) {
            Canvas(Modifier.size(logoSize * (176f / 248f))) {
                val canvas = drawContext.canvas.nativeCanvas
                val saved = canvas.save()
                canvas.scale(size.width / movie.width(), size.height / movie.height())
                movie.setTime((frameProgress.value * frameDuration).toInt())
                movie.draw(canvas, 0f, 0f)
                canvas.restoreToCount(saved)
            }
        } else {
            Image(
                painter = painterResource(R.drawable.pinkcollab_logo),
                contentDescription = null,
                modifier = Modifier.size(logoSize * (176f / 248f)),
            )
        }
    }
}
