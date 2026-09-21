package dev.pinkcollab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pinkcollab.data.Listing
import dev.pinkcollab.ui.theme.*

@Composable
internal fun DirectoryBrowserScreen(
    listing: LoadState<Listing>,
    hostName: String,
    creating: Boolean,
    browse: (String) -> Unit,
    select: (String) -> Unit,
    retry: () -> Unit,
) {
    when (listing) {
        LoadState.Loading -> EmptyState("Reading directory", "Loading this workspace from $hostName…")
        is LoadState.Failed -> EmptyState("Directory unavailable", listing.message, "Retry", retry)
        is LoadState.Ready -> DirectoryListing(listing.value, hostName, creating, browse, select)
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

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(16.dp).glassPanel(CardShape).padding(16.dp)) {
            Text(hostName, style = MaterialTheme.typography.labelLarge, color = Purple400)
            Spacer(Modifier.height(6.dp))
            Text(listing.path, style = MaterialTheme.typography.titleMedium)
            listing.branch?.let {
                Spacer(Modifier.height(8.dp))
                Text("Git · $it", style = MaterialTheme.typography.labelLarge, color = Violet400)
                Spacer(Modifier.height(4.dp))
                Text(
                    listing.gitStatus?.ifEmpty { "Working tree clean" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMid,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PrimaryButton(onClick = { select(listing.path) }, enabled = !creating) {
                    if (creating) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (creating) "Creating task…" else "Create task here")
                }
            }
        }
        HorizontalDivider(color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.08f))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) {
            listing.parent?.let { parent ->
                item {
                    TextButton(
                        onClick = rememberHapticOnClick { browse(parent) },
                        colors = ButtonDefaults.textButtonColors(contentColor = Purple200),
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
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = TextHigh),
                ) {
                    Icon(Icons.Outlined.Folder, null, tint = Purple400)
                    Spacer(Modifier.width(12.dp))
                    Text(directory.name, Modifier.weight(1f))
                    Icon(Icons.Outlined.ChevronRight, null, tint = Gray400)
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
