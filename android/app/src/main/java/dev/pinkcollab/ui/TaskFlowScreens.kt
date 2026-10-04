package dev.pinkcollab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pinkcollab.data.Listing
import dev.pinkcollab.ui.theme.*

@Composable
internal fun DirectoryBrowserScreen(
    state: LoadState<Listing>?,
    hostName: String,
    creating: Boolean,
    browse: (String) -> Unit,
    select: (String) -> Unit,
    retry: () -> Unit,
) {
    when (state) {
        null, LoadState.Loading -> DirectoryLoadingState(hostName)
        is LoadState.Failed -> EmptyState("Directory unavailable", state.message, "Retry", retry)
        is LoadState.Ready -> DirectoryListing(
            listing = state.value,
            hostName = hostName,
            creating = creating,
            browse = browse,
            select = select,
        )
    }
}

@Composable
private fun DirectoryListing(
    listing: Listing,
    hostName: String,
    creating: Boolean,
    browse: (String) -> Unit,
    select: (String) -> Unit,
) {

    Column(Modifier.fillMaxSize().retroBackdrop()) {
        Column(Modifier.fillMaxWidth().padding(16.dp).retroPanel().padding(16.dp)) {
            Text(hostName, style = MaterialTheme.typography.labelLarge, color = RetroBrass)
            Spacer(Modifier.height(6.dp))
            Text(listing.path, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PrimaryButton(onClick = { select(listing.path) }, enabled = !creating) {
                    if (creating) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (creating) "Creating session…" else "Create session here")
                }
            }
        }
        HorizontalDivider(color = RetroBrass.copy(alpha = 0.2f))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
            listing.parent?.let { parent ->
                item {
                    TextButton(
                        onClick = rememberHapticOnClick { browse(parent) },
                        colors = ButtonDefaults.textButtonColors(contentColor = RetroBrass),
                    ) {
                        Icon(Icons.Outlined.ArrowUpward, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Parent directory")
                    }
                }
            }
            items(listing.directories, key = { it.path }) { directory ->
                TextButton(
                    onClick = rememberHapticOnClick { browse(directory.path) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).retroPanel(inset = true),
                    colors = ButtonDefaults.textButtonColors(contentColor = TextHigh),
                ) {
                    Icon(Icons.Outlined.Folder, null, tint = RetroBrass)
                    Spacer(Modifier.width(12.dp))
                    Text(directory.name, Modifier.weight(1f))
                    Icon(Icons.Outlined.ChevronRight, null, tint = MutedText)
                }
            }
            if (listing.directories.isEmpty()) {
                item {
                    Text(
                        "No subdirectories to browse",
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMid,
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectoryLoadingState(hostName: String) {
    val pulse = 0.65f
    Column(Modifier.fillMaxSize().retroBackdrop().semantics(mergeDescendants = true) {}) {
        Column(Modifier.fillMaxWidth().padding(16.dp).retroPanel().padding(16.dp)) {
            Text(hostName, style = MaterialTheme.typography.labelLarge, color = RetroBrass)
            Spacer(Modifier.height(10.dp))
            DirectoryPlaceholder(0.72f, 18.dp, pulse)
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DirectoryPlaceholder(0.38f, 42.dp, pulse)
            }
        }
        LinearProgressIndicator(
            Modifier.fillMaxWidth(),
            color = RetroBrass,
            trackColor = RetroInsetTop,
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            repeat(6) { index ->
                Row(
                    Modifier.fillMaxWidth().height(52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = RetroBrass.copy(alpha = 0.45f),
                    )
                    Spacer(Modifier.width(14.dp))
                    DirectoryPlaceholder(if (index % 3 == 0) 0.62f else 0.46f, 12.dp, pulse)
                }
            }
        }
    }
}

@Composable
private fun RowScope.DirectoryPlaceholder(width: Float, height: Dp, pulse: Float) {
    Box(
        Modifier
            .fillMaxWidth(width)
            .height(height)
            .background(RetroBrass.copy(alpha = 0.11f * pulse), RoundedCornerShape(3.dp)),
    )
}

@Composable
private fun DirectoryPlaceholder(width: Float, height: Dp, pulse: Float) {
    Box(
        Modifier
            .fillMaxWidth(width)
            .height(height)
            .background(RetroBrass.copy(alpha = 0.11f * pulse), RoundedCornerShape(3.dp)),
    )
}
