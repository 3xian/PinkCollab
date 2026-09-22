package dev.pinkcollab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pinkcollab.data.*
import dev.pinkcollab.ui.theme.*

@Composable
internal fun ResourcesScreen(
    app: AppState,
    hostBusy: (String) -> Boolean,
    browse: (String, String) -> Unit,
    pair: () -> Unit,
    refresh: (String) -> Unit,
    forget: (String) -> Unit,
) {
    var hostPendingRemoval by remember { mutableStateOf<Host?>(null) }

    if (app.hosts.isEmpty()) {
        EmptyState("No workspaces yet", "Connect a host to see the project directories it allows.", "Connect host", pair)
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(app.hosts.values.toList(), key = { it.paired.host.id }) { host ->
            val hostId = host.paired.host.id
            val busy = hostBusy(hostId)
            val activeTasks = host.sessions.count { it.isActive }
            val (connectionLabel, connectionColor) = when (host.connection) {
                ConnectionState.Connecting -> "Connecting…" to Amber300
                ConnectionState.Synchronizing -> "Syncing…" to Amber300
                is ConnectionState.Online -> "Online" to Teal300
                is ConnectionState.Reconnecting -> "Reconnecting…" to Amber300
                is ConnectionState.Offline -> "Offline" to Gray400
                ConnectionState.AuthenticationRequired -> "Reconnect required" to Red400
            }
            Card(
                Modifier.fillMaxWidth().glassPanel(CardShape),
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = Color.Transparent, contentColor = TextHigh),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(42.dp).background(Purple400.copy(alpha = 0.12f), RoundedCornerShape(13.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.Dns, null, Modifier.size(22.dp), tint = Purple400)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(host.paired.host.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${host.paired.host.os} · $activeTasks active",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMid,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = rememberHapticOnClick { refresh(hostId) },
                            enabled = !busy,
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Purple200),
                        ) {
                            Icon(Icons.Outlined.Refresh, "Refresh host")
                        }
                        IconButton(
                            onClick = rememberHapticOnClick { hostPendingRemoval = host.paired.host },
                            enabled = !busy,
                            colors = IconButtonDefaults.iconButtonColors(contentColor = Gray400),
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, "Remove host")
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(host.paired.url, style = MaterialTheme.typography.bodySmall, color = Gray400, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "OMP ${host.paired.host.ompVersion} · Gateway ${host.paired.host.gatewayVersion}",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = Gray400,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(6.dp).background(connectionColor, CircleShape))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            connectionLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = connectionColor,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    Spacer(Modifier.height(12.dp))
                    if (host.workspaces.isEmpty()) {
                        Text(
                            when (host.connection) {
                                ConnectionState.Connecting, ConnectionState.Synchronizing -> "Loading allowed directories"
                                is ConnectionState.Online -> "No allowed directories"
                                is ConnectionState.Reconnecting -> "Reconnecting to load allowed directories"
                                is ConnectionState.Offline -> "Reconnect this host to load its directories"
                                ConnectionState.AuthenticationRequired -> "Pair this host again to load its directories"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Gray400,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            host.workspaces.forEach { workspace ->
                                WorkspaceRow(workspace, host.connected) { browse(hostId, workspace.path) }
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                OutlinedButton(onClick = rememberHapticOnClick(pair)) {
                    Icon(Icons.Outlined.AddLink, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Connect another host")
                }
            }
        }
    }
    hostPendingRemoval?.let { host ->
        AlertDialog(
            onDismissRequest = { hostPendingRemoval = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, null, tint = Red400) },
            title = { Text("Remove host?") },
            text = { Text("This removes “${host.name}” and its saved connection from this phone. The host itself will not be changed.") },
            confirmButton = {
                TextButton(
                    onClick = rememberHapticOnClick {
                        hostPendingRemoval = null
                        forget(host.id)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Red400),
                ) {
                    Text("Remove")
                }
            },
            dismissButton = { TextButton(onClick = rememberHapticOnClick { hostPendingRemoval = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WorkspaceRow(workspace: Workspace, enabled: Boolean, onClick: () -> Unit) {

    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = if (enabled) 0.055f else 0.025f), shape)
            .clickable(enabled = enabled, onClick = rememberHapticOnClick(onClick))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(21.dp), tint = if (enabled) Purple400 else Gray400)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(workspace.name, style = MaterialTheme.typography.titleSmall, color = if (enabled) TextHigh else Gray400)
            Text(workspace.path, style = MaterialTheme.typography.bodySmall, color = Gray400, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = Gray400)
    }
}
