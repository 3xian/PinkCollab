package dev.pinkcollab.ui

import dev.pinkcollab.data.ConnectionState

internal fun workspaceConnectionMessage(host: NavigationHost): String = when (val connection = host.connection) {
    ConnectionState.Connecting -> "Opening connection to host"
    ConnectionState.Synchronizing -> "Connection established; syncing sessions and directories"
    ConnectionState.Reconnecting -> {
        val progress = host.connectionProgress
        val retry = progress?.let { "Retrying automatically (attempt ${it.attempt})" }
            ?: "Retrying connection automatically"
        progress?.failure?.let { "$it. $retry" } ?: retry
    }
    is ConnectionState.Online -> "No allowed directories"
    is ConnectionState.Offline -> if (host.connectionProgress != null) {
        "${connection.reason ?: "Host unavailable"}. Checking periodically; refresh to retry now"
    } else connection.reason ?: "Reconnect this host to load its directories"
    ConnectionState.AuthenticationRequired -> "Host sign-in expired; pair this host again"
    ConnectionState.UpgradeRequired -> "Host requires a newer PinkCollab version"
}
