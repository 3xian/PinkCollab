package dev.pinkcollab.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pinkcollab.R
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.ConnectionFailure
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.InitialSyncState
import dev.pinkcollab.ui.theme.*

internal fun recoveryHostDetail(host: HostState): String = when (val connection = host.connection) {
    ConnectionState.Connecting -> "Reconnecting…"
    ConnectionState.Synchronizing -> "Loading sessions…"
    is ConnectionState.Online -> if (host.initialSync == InitialSyncState.Ready) "No sessions yet."
        else "Couldn’t load sessions from this host."
    ConnectionState.AuthenticationRequired -> "Pair this host again to reconnect."
    ConnectionState.UpgradeRequired -> "Update PinkCollab to connect to this host."
    is ConnectionState.Offline -> when (connection.failure) {
        ConnectionFailure.NetworkUnavailable -> "Check your phone’s internet connection."
        ConnectionFailure.HostNotFound -> "Couldn’t find this host."
        ConnectionFailure.TimedOut -> "The host didn’t respond in time."
        ConnectionFailure.SecureConnectionFailed -> "Couldn’t establish a secure connection."
        else -> "The host is temporarily unavailable."
    }
}

internal data class RecoveryPrimaryAction(val label: String, val enabled: Boolean, val onClick: () -> Unit)

internal fun recoveryPrimaryAction(hosts: List<HostState>, actions: TasksScreenActions): RecoveryPrimaryAction {
    val retryIds = hosts.filter {
        it.connection is ConnectionState.Offline ||
            (it.connection is ConnectionState.Online && it.initialSync != InitialSyncState.Ready)
    }.map { it.paired.host.id }
    val pendingIds = hosts.filter {
        it.connection == ConnectionState.Connecting || it.connection == ConnectionState.Synchronizing
    }.map { it.paired.host.id }
    return when {
        retryIds.isNotEmpty() -> RecoveryPrimaryAction("Try again", true) {
            (retryIds + pendingIds).forEach(actions.retryHost)
        }
        hosts.any { it.connection == ConnectionState.AuthenticationRequired } ->
            RecoveryPrimaryAction("Reconnect host", true, actions.connectHost)
        hosts.any { it.connection == ConnectionState.UpgradeRequired } ->
            RecoveryPrimaryAction("Update app", true, actions.checkForUpdates)
        pendingIds.isNotEmpty() -> RecoveryPrimaryAction("Try again", true) {
            pendingIds.forEach(actions.retryHost)
        }
        else -> RecoveryPrimaryAction("Try again", false) {}
    }
}

@Composable
internal fun SessionRecoveryScreen(hosts: List<HostState>, actions: TasksScreenActions) {
    val primaryAction = recoveryPrimaryAction(hosts, actions)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.7f).heightIn(min = 24.dp))
            Image(
                painter = painterResource(R.drawable.disconnected_robot),
                contentDescription = null,
                modifier = Modifier.widthIn(max = 140.dp).fillMaxWidth().aspectRatio(1.25f)
                    .drawWithCache {
                        val radius = size.width * 0.7f
                        val glowCenter = Offset(size.width / 2f, size.height / 2f)
                        val glow = Brush.radialGradient(
                            0f to Teal300.copy(alpha = 0.30f),
                            0.45f to Teal300.copy(alpha = 0.14f),
                            1f to Color.Transparent,
                            center = glowCenter,
                            radius = radius,
                        )
                        onDrawBehind { drawCircle(glow, radius = radius, center = glowCenter) }
                    },
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                if (hosts.any { it.connected }) "Couldn’t load sessions" else "Can’t reach your hosts",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (hosts.any { it.connected }) "Try again to load your sessions."
                else "Reconnect a host to load your sessions.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMid,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                hosts.forEach { host ->
                    Column(Modifier.glassPanel(CardShape).padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                host.paired.host.name,
                                Modifier.weight(1f).padding(end = 12.dp),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            val (label, color) = when (host.connection) {
                                ConnectionState.Connecting -> "Reconnecting" to BrandPink
                                ConnectionState.Synchronizing -> "Syncing" to BrandPink
                                is ConnectionState.Online -> "Connected" to Teal300
                                is ConnectionState.Offline -> "Offline" to Gray400
                                ConnectionState.AuthenticationRequired -> "Reconnect required" to Red400
                                ConnectionState.UpgradeRequired -> "Update required" to Red400
                            }
                            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(recoveryHostDetail(host), style = MaterialTheme.typography.bodySmall, color = TextMid)
                        if (host.connection == ConnectionState.AuthenticationRequired) {
                            TextButton(onClick = rememberHapticOnClick(actions.connectHost)) { Text("Reconnect host") }
                        } else if (host.connection == ConnectionState.UpgradeRequired) {
                            TextButton(onClick = rememberHapticOnClick(actions.checkForUpdates)) { Text("Update app") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                onClick = rememberHapticOnClick(primaryAction.onClick),
                enabled = primaryAction.enabled,
            ) {
                Text(primaryAction.label)
            }
            TextButton(onClick = rememberHapticOnClick(actions.openResources)) { Text("Open workspaces") }
            Spacer(Modifier.weight(1f).heightIn(min = 24.dp))
        }
    }
}
