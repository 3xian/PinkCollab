package dev.pinkcollab.ui

import android.graphics.Movie
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import dev.pinkcollab.R
import dev.pinkcollab.data.AppState
import dev.pinkcollab.data.TaskListLoadState
import dev.pinkcollab.ui.theme.RetroBrass
import dev.pinkcollab.ui.theme.retroBackdrop
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

internal suspend fun awaitStartupReadiness(
    appStates: StateFlow<AppState>,
) {
    appStates.first { it.taskListLoadState != TaskListLoadState.Loading }
}

@Composable
internal fun StartupLoadingScreen(app: NavigationState) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .retroBackdrop(),
        contentAlignment = Alignment.Center,
    ) {
        StartupProgress(app, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp))
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                Text(
                    "PinkCollab",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                AnimatedLoadingLogo()
            },
        ) { measurables, constraints ->
            val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
            val title = measurables[0].measure(childConstraints)
            val logo = measurables[1].measure(childConstraints)
            val gap = 20.dp.roundToPx()
            layout(constraints.maxWidth, constraints.maxHeight) {
                val logoTop = (constraints.maxHeight - logo.height) / 2
                logo.placeRelative((constraints.maxWidth - logo.width) / 2, logoTop)
                title.placeRelative((constraints.maxWidth - title.width) / 2, logoTop - gap - title.height)
            }
        }
    }
}

@Composable
internal fun AnimatedLoadingLogo(logoSize: Dp = 248.dp) {
    val context = LocalContext.current
    val movie = remember(context) {
        context.resources.openRawResource(R.raw.login_logo).use(Movie::decodeStream)
    }
    val artworkPaint = remember {
        android.graphics.Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(
                android.graphics.ColorMatrix(floatArrayOf(
                    0.30f, 0.59f, 0.11f, 0f, 0f,
                    0.25f, 0.49f, 0.09f, 0f, 0f,
                    0.17f, 0.33f, 0.06f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )),
            )
        }
    }
    val frameDuration = movie?.duration()?.takeIf { it > 0 } ?: 1920
    val motion = rememberInfiniteTransition(label = "loadingCharacter")
    val frameProgress = motion.animateFloat(0f, 1f,
        infiniteRepeatable(tween(frameDuration, easing = LinearEasing)), label = "gifFrame")

    Box(
        modifier = Modifier.size(logoSize),
        contentAlignment = Alignment.Center,
    ) {
        if (movie != null) {
            Canvas(Modifier.size(logoSize * (176f / 248f))) {
                val canvas = drawContext.canvas.nativeCanvas
                val saved = canvas.save()
                canvas.scale(size.width / movie.width(), size.height / movie.height())
                movie.setTime((frameProgress.value * frameDuration).toInt())
                movie.draw(canvas, 0f, 0f, artworkPaint)
                canvas.restoreToCount(saved)
            }
        } else {
            Image(
                painter = painterResource(R.drawable.pinkcollab_logo),
                contentDescription = null,
                modifier = Modifier.size(logoSize * (176f / 248f)),
                colorFilter = ColorFilter.tint(RetroBrass),
            )
        }
    }
}
