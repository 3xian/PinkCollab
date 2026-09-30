package dev.pinkcollab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.pinkcollab.ui.theme.PinkCollabTypography
import kotlinx.coroutines.delay

/** A small progress row also works above retained messages during a refresh. */
@Composable
internal fun SessionSyncProgress(message: String, modifier: Modifier = Modifier) {
    var slow by remember(message) { mutableStateOf(false) }
    LaunchedEffect(message) {
        delay(8_000)
        slow = true
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
            Text(message, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                style = PinkCollabTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (slow) Text("Taking longer than usual. You can switch sessions while this loads.",
            style = PinkCollabTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun TimelineLoadingState(
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        SessionSyncProgress(message)
        // Static placeholders avoid distracting animation while keeping the conversation layout visible.
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            repeat(3) { index ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.width(if (index == 1) 72.dp else 48.dp).height(10.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f), RoundedCornerShape(5.dp)))
                    repeat(if (index == 1) 3 else 2) { line ->
                        Spacer(Modifier.fillMaxWidth(if (line == 1) 0.65f else 0.9f).height(12.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(6.dp)))
                    }
                }
            }
        }
    }
}
