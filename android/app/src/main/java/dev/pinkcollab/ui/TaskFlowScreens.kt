package dev.pinkcollab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.Listing
import dev.pinkcollab.ui.theme.*

@Composable
internal fun DirectoryBrowserScreen(
    listing: LoadState<Listing>,
    hostName: String,
    browse: (String) -> Unit,
    select: (String) -> Unit,
    retry: () -> Unit,
) {
    when (listing) {
        LoadState.Loading -> EmptyState("Reading directory", "Loading this workspace from $hostName…")
        is LoadState.Failed -> EmptyState("Directory unavailable", listing.message, "Retry", retry)
        is LoadState.Ready -> DirectoryListing(listing.value, hostName, browse, select)
    }
}

@Composable
private fun DirectoryListing(
    listing: Listing,
    hostName: String,
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
                PrimaryButton(onClick = { select(listing.path) }) { Text("Create task here") }
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

@Composable
internal fun CreateTaskScreen(
    host: HostState?,
    cwd: String,
    busy: Boolean,
    changeDirectory: () -> Unit,
    start: (String) -> Unit,
) {
    var prompt by rememberSaveable(cwd) { mutableStateOf("") }

    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Host", style = MaterialTheme.typography.labelMedium, color = TextMid)
            Text(host?.paired?.host?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
        }
        item {
            Text("Working directory", style = MaterialTheme.typography.labelMedium, color = TextMid)
            Text(cwd)
            TextButton(
                onClick = rememberHapticOnClick(changeDirectory),
                enabled = !busy,
                colors = ButtonDefaults.textButtonColors(contentColor = Purple200),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("Change")
            }
        }
        item {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("What should OMP do?") },
                placeholder = { Text("Fix the checkout race and run the tests") },
                minLines = 5,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PrimaryButton(
                    onClick = { start(prompt) },
                    enabled = prompt.isNotBlank() && !busy && host?.connected == true,
                ) {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (busy) "Starting OMP…" else "Start task")
                }
            }
        }
    }
}

@Composable
internal fun PairHostScreen(
    url: String,
    token: String,
    busy: Boolean,
    onURL: (String) -> Unit,
    onToken: (String) -> Unit,
    scan: () -> Unit,
    pair: () -> Unit,
) {

    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("Connect your OMP host", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "Run the pair command on the host and scan the QR code it prints. The code expires in 5 minutes.",
                color = TextMid,
            )
        }
        item {
            OutlinedButton(onClick = rememberHapticOnClick(scan), enabled = !busy) {
                Icon(Icons.Outlined.QrCodeScanner, null)
                Spacer(Modifier.width(8.dp))
                Text("Scan pairing code")
            }
        }
        item { Text("Or enter it manually", style = MaterialTheme.typography.labelLarge, color = TextMid) }
        item {
            OutlinedTextField(
                url,
                onURL,
                label = { Text("Gateway address") },
                placeholder = { Text("https://dev-server.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
        }
        item {
            OutlinedTextField(
                token,
                onToken,
                label = { Text("One-time pairing token") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PrimaryButton(onClick = pair, enabled = url.isNotBlank() && token.isNotBlank() && !busy) {
                    Text("Pair")
                }
            }
        }
    }
}
