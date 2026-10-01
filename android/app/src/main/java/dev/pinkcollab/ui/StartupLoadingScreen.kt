package dev.pinkcollab.ui

import android.graphics.Movie
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import dev.pinkcollab.R
import dev.pinkcollab.data.AppState
import dev.pinkcollab.data.TaskListLoadState
import dev.pinkcollab.ui.theme.Base0
import dev.pinkcollab.ui.theme.BrandPink
import dev.pinkcollab.ui.theme.BrandPurple
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.roundToInt

private const val StartupLoadingTimeoutMillis = 8_000L

internal suspend fun awaitStartupReadiness(
    appStates: StateFlow<AppState>,
    maximumDurationMillis: Long = StartupLoadingTimeoutMillis,
) = coroutineScope {
    if (appStates.value.taskListLoadState == TaskListLoadState.Loading) {
        withTimeoutOrNull(maximumDurationMillis) {
            appStates.first { it.taskListLoadState != TaskListLoadState.Loading }
        }
    }
}

@Composable
internal fun StartupLoadingScreen(app: NavigationState) {
    val motion = rememberInfiniteTransition(label = "startup")
    val progress = motion.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1350, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "loadingLine",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Base0),
        contentAlignment = Alignment.Center,
    ) {
        StartupProgress(app, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 36.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AnimatedLoadingLogo()
            Spacer(Modifier.height(20.dp))
            Text(
                "PinkCollab",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .width(112.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.08f)),
            ) {
                Box(
                    Modifier
                        .offset { IntOffset((progress.value * 78.dp.toPx()).roundToInt(), 0) }
                        .graphicsLayer {
                            alpha = ((1f - abs(progress.value)) / 0.28f).coerceIn(0f, 1f)
                        }
                        .width(48.dp)
                        .height(2.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color.Transparent,
                                    BrandPurple,
                                    BrandPink,
                                    Color.Transparent,
                                ),
                            ),
                            RoundedCornerShape(999.dp),
                        ),
                )
            }
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
        Canvas(Modifier.fillMaxSize()) {
            val phase = glowProgress?.value ?: 0.25f
            val wave = sin(PI * phase).toFloat()
            val breath = wave * wave
            val center = Offset(size.width * 0.51f, size.height * 0.5f)
            val radius = size.width * 0.43f * glowScale * (0.88f + 0.12f * breath)
            val opacity = 0.62f * (0.58f + 0.42f * breath)
            drawCircle(
                brush = Brush.radialGradient(
                    0f to BrandPink.copy(alpha = opacity),
                    0.4f to BrandPink.copy(alpha = opacity * 0.78f),
                    1f to BrandPink.copy(alpha = 0f),
                    center = center,
                    radius = radius,
                ),
                center = center,
                radius = radius,
            )
        }
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
