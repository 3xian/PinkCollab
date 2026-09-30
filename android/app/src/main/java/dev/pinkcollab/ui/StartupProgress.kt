package dev.pinkcollab.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.InitialSyncState
import dev.pinkcollab.ui.theme.TextMid

internal data class StartupStep(val key: String, val text: String)

internal fun startupSteps(app: NavigationState): List<StartupStep> {
    if (app.loadingCredentials) return listOf(StartupStep("credentials", "Loading paired hosts"))
    if (app.hosts.isEmpty()) return listOf(StartupStep("hosts", "Ready to connect your first host"))
    return app.hosts.map { (id, host) ->
        val name = host.host.name
        val text = when (host.connection) {
            ConnectionState.Connecting -> "Connecting to $name"
            ConnectionState.Synchronizing -> "Syncing sessions · $name"
            ConnectionState.Reconnecting -> "Reconnecting to $name"
            ConnectionState.AuthenticationRequired -> "Sign-in required · $name"
            ConnectionState.UpgradeRequired -> "Update required · $name"
            is ConnectionState.Offline -> "Host offline · $name"
            is ConnectionState.Online -> when (host.initialSync) {
                InitialSyncState.Pending -> "Loading sessions · $name"
                InitialSyncState.Ready -> "Sessions ready · $name"
                InitialSyncState.Unavailable -> "Session sync unavailable · $name"
            }
        }
        StartupStep(id, text)
    }
}

@Composable
internal fun StartupProgress(app: NavigationState, modifier: Modifier = Modifier) {
    val entries = remember { mutableStateListOf<StartupStep>() }
    val latest = remember { mutableMapOf<String, String>() }
    val steps = startupSteps(app)
    val scroll = rememberLazyListState()
    LaunchedEffect(steps) {
        steps.forEach { step ->
            if (latest.put(step.key, step.text) != step.text) entries.add(step)
        }
        while (entries.size > 40) entries.removeAt(0)
    }
    LaunchedEffect(entries.toList()) {
        if (entries.isNotEmpty()) scroll.animateScrollToItem(entries.lastIndex)
    }
    LazyColumn(
        state = scroll,
        modifier = modifier.fillMaxWidth().height(108.dp).testTag("startupProgress")
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.3f to Color.Black,
                        0.7f to Color.Black,
                        1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
        contentPadding = PaddingValues(vertical = 40.dp),
    ) {
        itemsIndexed(entries) { index, step ->
            Text(
                step.text,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 5.dp),
                style = MaterialTheme.typography.bodySmall,
                color = TextMid.copy(alpha = if (index == entries.lastIndex) 0.8f else 0.55f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
