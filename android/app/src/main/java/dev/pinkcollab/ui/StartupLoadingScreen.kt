package dev.pinkcollab.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
            Image(
                painter = painterResource(R.drawable.pinkcollab_logo),
                contentDescription = null,
                modifier = Modifier
                    .width(88.dp)
                    .height(88.dp)
                    .clip(RoundedCornerShape(20.dp)),
            )
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
