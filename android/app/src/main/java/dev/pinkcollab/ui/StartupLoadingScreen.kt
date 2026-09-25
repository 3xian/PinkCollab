package dev.pinkcollab.ui

import android.graphics.Movie
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pinkcollab.R
import dev.pinkcollab.data.AppState
import dev.pinkcollab.data.InitialSyncTimeoutMillis
import dev.pinkcollab.data.TaskListLoadState
import dev.pinkcollab.ui.theme.Base0
import dev.pinkcollab.ui.theme.BrandPink
import dev.pinkcollab.ui.theme.BrandPurple
import dev.pinkcollab.ui.theme.TextMid
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

internal suspend fun awaitStartupReadiness(
    appStates: StateFlow<AppState>,
    maximumDurationMillis: Long = InitialSyncTimeoutMillis,
) {
    if (appStates.value.taskListLoadState != TaskListLoadState.Loading) return
    withTimeoutOrNull(maximumDurationMillis) {
        appStates.first { it.taskListLoadState != TaskListLoadState.Loading }
    }
}

@Composable
internal fun StartupLoadingScreen() {
    val motion = rememberInfiniteTransition(label = "startup")
    val progress by motion.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1350, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "loadingLine",
    )
    val captionAlpha by motion.animateFloat(
        initialValue = 0.48f,
        targetValue = 0.88f,
        animationSpec = infiniteRepeatable(
            animation = tween(1050, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "caption",
    )
    val loadingLineAlpha = ((1f - abs(progress)) / 0.28f).coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Base0),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AnimatedStartupLogo()
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
                        .offset(x = (progress * 78f).dp)
                        .alpha(loadingLineAlpha)
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
            Spacer(Modifier.height(16.dp))
            Text(
                "Syncing OMP sessions…",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMid.copy(alpha = captionAlpha),
            )
        }
    }
}

@Composable
private fun AnimatedStartupLogo() {
    val context = LocalContext.current
    val movie = remember(context) {
        context.resources.openRawResource(R.raw.login_logo).use(Movie::decodeStream)
    }
    var elapsedMillis by remember { mutableLongStateOf(0L) }
    LaunchedEffect(movie) {
        var startNanos = 0L
        while (true) {
            withFrameNanos { now ->
                if (startNanos == 0L) startNanos = now
                elapsedMillis = (now - startNanos) / 1_000_000L
            }
        }
    }

    Box(
        modifier = Modifier
            .size(176.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFFF7F7FA))
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (movie != null) {
            Canvas(Modifier.fillMaxSize()) {
                val frameDuration = movie.duration().takeIf { it > 0 } ?: 1920
                val canvas = drawContext.canvas.nativeCanvas
                val saved = canvas.save()
                canvas.scale(size.width / movie.width(), size.height / movie.height())
                movie.setTime((elapsedMillis % frameDuration).toInt())
                movie.draw(canvas, 0f, 0f)
                canvas.restoreToCount(saved)
            }
        } else {
            Image(
                painter = painterResource(R.drawable.pinkcollab_logo),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
