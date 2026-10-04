package dev.pinkcollab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pinkcollab.data.*
import dev.pinkcollab.ui.theme.*

@Composable
internal fun ResourcesScreen(
    app: NavigationState,
    hostBusy: (String) -> Boolean,
    browse: (String, String) -> Unit,
    pair: () -> Unit,
    refresh: (String) -> Unit,
    forget: (String) -> Unit,
) {
    var hostPendingRemoval by remember { mutableStateOf<Host?>(null) }

    if (app.hosts.isEmpty()) {
        Column(
            Modifier.fillMaxSize().retroBackdrop().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 32.dp).padding(top = 48.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "No workspaces yet",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Connect a host to see the project directories it allows.",
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            PrimaryButton(onClick = pair) { Text("Connect host") }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().retroBackdrop(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(app.hosts.values.toList(), key = { it.host.id }) { host ->
            val hostId = host.host.id
            val busy = hostBusy(hostId)
            val activeTasks = host.activeTasks
            val connectionPending = host.connection == ConnectionState.Connecting ||
                host.connection == ConnectionState.Synchronizing
            val (connectionLabel, connectionColor) = when (host.connection) {
                ConnectionState.Connecting, ConnectionState.Synchronizing ->
                    (if ((host.connectionProgress?.attempt ?: 1) > 1) "Reconnecting" else "Connecting") to RetroBrass
                is ConnectionState.Online -> "$activeTasks active" to Color(0xFFA8B581)
                is ConnectionState.Offline -> "Offline" to MutedText
                ConnectionState.AuthenticationRequired -> "Reconnect required" to ErrorRed
                ConnectionState.UpgradeRequired -> "App update required" to ErrorRed
            }
            Card(
                Modifier.fillMaxWidth().retroPanel().clip(RoundedCornerShape(3.dp)).workspaceBackdrop(host.host.os),
                shape = RoundedCornerShape(3.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent, contentColor = TextHigh),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Dns, null, Modifier.size(22.dp), tint = workspaceIconColor(host.host.os))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(host.host.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                        IconButton(
                            onClick = rememberHapticOnClick { refresh(hostId) },
                            enabled = !busy,
                            colors = IconButtonDefaults.iconButtonColors(contentColor = RetroBrass),
                        ) {
                            Icon(Icons.Outlined.Refresh, "Refresh host")
                        }
                        IconButton(
                            onClick = rememberHapticOnClick { hostPendingRemoval = host.host },
                            enabled = !busy,
                            colors = IconButtonDefaults.iconButtonColors(contentColor = MutedText),
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, "Remove host")
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    HostUrlRow(host.url)
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "OMP ${host.host.ompVersion} · Gateway ${host.host.gatewayVersion}",
                            Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedText,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).background(connectionColor, CircleShape))
                            Spacer(Modifier.width(5.dp))
                            if (connectionPending) {
                                ConnectingLabel(connectionLabel, connectionColor)
                            } else {
                                Text(
                                    connectionLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = connectionColor,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (!host.connected || host.workspaces.isEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                Icons.Outlined.ChatBubbleOutline,
                                contentDescription = null,
                                modifier = Modifier.padding(top = 2.dp).size(14.dp),
                                tint = MutedText,
                            )
                            Text(
                                workspaceConnectionMessage(host),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MutedText,
                            )
                        }
                    }
                    if (host.workspaces.isNotEmpty()) {
                        if (!host.connected) Spacer(Modifier.height(8.dp))
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
                OutlinedButton(onClick = rememberHapticOnClick(pair), shape = RetroShape) {
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
            icon = { Icon(Icons.Outlined.DeleteOutline, null, tint = ErrorRed) },
            title = { Text("Remove host?") },
            text = { Text("This removes “${host.name}” and its saved connection from this phone. The host itself will not be changed.") },
            confirmButton = {
                TextButton(
                    onClick = rememberHapticOnClick {
                        hostPendingRemoval = null
                        forget(host.id)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = ErrorRed),
                ) {
                    Text("Remove")
                }
            },
            dismissButton = { TextButton(onClick = rememberHapticOnClick { hostPendingRemoval = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConnectingLabel(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun HostUrlRow(url: String) {
    var visible by rememberSaveable(url) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (visible) url else "https://••••",
            Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodySmall,
            color = MutedText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(
            onClick = rememberHapticOnClick { visible = !visible },
            modifier = Modifier.size(36.dp),
            colors = IconButtonDefaults.iconButtonColors(contentColor = MutedText),
        ) {
            Icon(
                if (visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                contentDescription = if (visible) "Hide URL" else "Show URL",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun WorkspaceRow(workspace: Workspace, enabled: Boolean, onClick: () -> Unit) {

    val shape = RoundedCornerShape(3.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .retroPanel(inset = true)
            .clickable(enabled = enabled, onClick = rememberHapticOnClick(onClick))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(21.dp), tint = if (enabled) RetroBrass else RetroMutedText)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(workspace.name, style = MaterialTheme.typography.titleSmall, color = if (enabled) TextHigh else MutedText)
            Text(workspace.path, style = MaterialTheme.typography.bodySmall, color = MutedText, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(20.dp), tint = MutedText)
    }
}
