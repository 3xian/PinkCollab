package dev.pinkcollab.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pinkcollab.data.*
import dev.pinkcollab.ui.theme.*

@Composable
internal fun ResourcesScreen(
    app: NavigationState,
    hostBusy: (String) -> Boolean,
    browse: (String, String) -> Unit,
    pair: () -> Unit,
    refresh: (String) -> Unit,
    rename: (String, String) -> Unit,
    forget: (String) -> Unit,
) {
    var hostPendingRemoval by remember { mutableStateOf<NavigationHost?>(null) }
    var renaming by remember { mutableStateOf<NavigationHost?>(null) }

    if (app.hosts.isEmpty()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 32.dp).padding(top = 48.dp, bottom = 32.dp),
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
        modifier = Modifier.fillMaxSize(),
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
                    (if ((host.connectionProgress?.attempt ?: 1) > 1) "Reconnecting" else "Connecting") to BrandPink
                is ConnectionState.Online -> "$activeTasks active" to Teal300
                is ConnectionState.Offline -> "Offline" to Gray400
                ConnectionState.AuthenticationRequired -> "Reconnect required" to Red400
                ConnectionState.UpgradeRequired -> "App update required" to Red400
            }
            Card(
                Modifier.fillMaxWidth().glassPanel(CardShape, borderAlpha = 0f).clip(CardShape).workspaceBackdrop(host.host.os),
                shape = CardShape,
                colors = CardDefaults.cardColors(containerColor = Color.Transparent, contentColor = TextHigh),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Dns, null, Modifier.size(18.dp), tint = workspaceIconColor(host.host.os))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            host.displayName,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(
                            onClick = rememberHapticOnClick { refresh(hostId) },
                            enabled = !busy,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(Icons.Outlined.Refresh, "Refresh host", Modifier.size(18.dp))
                        }
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            IconButton(
                                onClick = rememberHapticOnClick { menuOpen = true },
                                enabled = !busy,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.Outlined.Edit, "Edit host", Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    onClick = rememberHapticOnClick {
                                        menuOpen = false
                                        renaming = host
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Remove") },
                                    onClick = rememberHapticOnClick {
                                        menuOpen = false
                                        hostPendingRemoval = host
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    HostUrlRow(host.url)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${host.host.ompVersion} · Gateway ${host.host.gatewayVersion}",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = Gray400,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(6.dp).background(connectionColor, CircleShape))
                        Spacer(Modifier.width(5.dp))
                        if (connectionPending) {
                            ConnectingLabel(connectionLabel, connectionColor)
                        } else {
                            Text(
                                connectionLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = connectionColor,
                                maxLines = 1,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (!host.connected || host.workspaces.isEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                Icons.Outlined.ChatBubbleOutline,
                                contentDescription = null,
                                modifier = Modifier.padding(top = 2.dp).size(14.dp),
                                tint = Gray400,
                            )
                            Text(
                                workspaceConnectionMessage(host),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = Gray400,
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
            text = { Text("This removes “${host.displayName}” and its saved connection from this phone. The host itself will not be changed.") },
            confirmButton = {
                TextButton(
                    onClick = rememberHapticOnClick {
                        hostPendingRemoval = null
                        forget(host.host.id)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Red400),
                ) {
                    Text("Remove")
                }
            },
            dismissButton = { TextButton(onClick = rememberHapticOnClick { hostPendingRemoval = null }) { Text("Cancel") } },
        )
    }
    renaming?.let { host ->
        var draft by remember(host.host.id) { mutableStateOf(host.displayName) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename host") },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.replace("\n", "") },
                        singleLine = true,
                        label = { Text("Name") },
                    )
                    if (host.host.name != host.displayName) {
                        TextButton(onClick = rememberHapticOnClick { draft = host.host.name }) {
                            Text("Use Gateway name “${host.host.name}”", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = rememberHapticOnClick {
                        rename(host.host.id, draft.trim())
                        renaming = null
                    },
                    enabled = draft.trim().isNotEmpty(),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = rememberHapticOnClick { renaming = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ConnectingLabel(text: String, color: Color) {
    var textWidth by remember { mutableFloatStateOf(0f) }
    val transition = rememberInfiniteTransition(label = "hostConnection")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_800, easing = LinearEasing)),
        label = "connectionFlow",
    )
    val streakWidth = textWidth * 0.38f
    val streakStart = -streakWidth + progress * (textWidth + streakWidth)
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(
            brush = Brush.linearGradient(
                colors = listOf(color, color, MaterialTheme.colorScheme.primary, color, color),
                start = Offset(streakStart, 0f),
                end = Offset(streakStart + streakWidth.coerceAtLeast(1f), 0f),
            ),
        ),
        onTextLayout = { textWidth = it.size.width.toFloat() },
    )
}

@Composable
private fun HostUrlRow(url: String) {
    var visible by rememberSaveable(url) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (visible) url else "https://••••",
            Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodySmall,
            color = Gray400,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(
            onClick = rememberHapticOnClick { visible = !visible },
            modifier = Modifier.size(36.dp),
            colors = IconButtonDefaults.iconButtonColors(contentColor = Gray400),
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
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = if (enabled) 0.055f else 0.025f), shape)
            .clickable(enabled = enabled, onClick = rememberHapticOnClick(onClick))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(workspace.path, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) TextHigh else Gray400)
    }
}
