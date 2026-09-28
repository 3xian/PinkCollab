package dev.pinkcollab.ui

import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SessionStatus

internal enum class SessionCardStatus(val label: String) {
    Connecting("Connecting"), Syncing("Syncing"), Reconnecting("Reconnecting"),
    Offline("Offline"), SignIn("Sign in"), UpdateRequired("Update required"),
    Starting("Starting"), Stopping("Stopping"), Working("Working"),
    NeedsInput("Needs you"), Ready("Ready"), Inactive("Inactive"), Unknown("Unknown"),
}

/** Host connectivity qualifies all retained runtime evidence. Idle alone is not a pause or completion. */
internal fun sessionCardStatus(session: Session, connection: ConnectionState?): SessionCardStatus {
    when (connection) {
        ConnectionState.Connecting -> return SessionCardStatus.Connecting
        ConnectionState.Synchronizing -> return SessionCardStatus.Syncing
        ConnectionState.Reconnecting -> return SessionCardStatus.Reconnecting
        ConnectionState.AuthenticationRequired -> return SessionCardStatus.SignIn
        ConnectionState.UpgradeRequired -> return SessionCardStatus.UpdateRequired
        is ConnectionState.Offline, null -> return SessionCardStatus.Offline
        is ConnectionState.Online -> Unit
    }
    return when {
        session.status == SessionStatus.Starting -> SessionCardStatus.Starting
        session.status == SessionStatus.Stopping -> SessionCardStatus.Stopping
        !session.runtimeAttached -> SessionCardStatus.Inactive
        session.attention != null || session.needsAttention || session.status == SessionStatus.NeedsInput -> SessionCardStatus.NeedsInput
        session.status == SessionStatus.Running -> SessionCardStatus.Working
        session.status == SessionStatus.Idle -> SessionCardStatus.Ready
        else -> SessionCardStatus.Unknown
    }
}
