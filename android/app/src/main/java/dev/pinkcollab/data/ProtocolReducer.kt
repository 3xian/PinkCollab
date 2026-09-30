package dev.pinkcollab.data

import org.json.JSONObject
import java.util.UUID

internal data class ProtocolReduction(val state: AppState, val effects: List<GatewayEffect> = emptyList())
internal sealed interface GatewayEffect {
    data class LoadHistory(val session: SessionKey) : GatewayEffect
}

/** Ordered socket events replace small objects; only the live timeline is incremental. */
internal fun reduceProtocol(app: AppState, hostId: String, frame: JSONObject, nowEpochMillis: Long): ProtocolReduction {
    val host = app.hosts[hostId] ?: return ProtocolReduction(app)
    val type = frame.getString("type")
    if (type == "host_snapshot") {
        require(frame.getInt("protocolVersion") == 3) { "Upgrade PinkCollab to connect to this Gateway" }
        val identity = frame.getJSONObject("host").host()
        require(identity.id == hostId) { "Gateway identity changed; pair again" }
        return ProtocolReduction(app.copy(
            hosts = app.hosts + (hostId to host.copy(
                paired = host.paired.copy(host = identity), connection = ConnectionState.Online(nowEpochMillis),
                connectionProgress = null,
                sessions = frame.getJSONArray("sessions").objects().map { it.sessionSummary() },
                workspaces = frame.getJSONArray("workspaces").objects().map { it.workspace() },
                snapshotToken = UUID.randomUUID().toString(), revision = host.revision + 1,
                lastSyncedAtEpochMillis = nowEpochMillis, initialSync = InitialSyncState.Ready,
            )), details = app.details.filterKeys { it.hostId != hostId },
        ))
    }
    val id = frame.optString("sessionId").ifBlank { frame.getJSONObject("session").getString("id") }
    val key = SessionKey(hostId, id)
    val before = app.details[key]
    var detail = before
    var sessions = host.sessions
    var loadHistory = false
    when (type) {
        "session_remove" -> { sessions = sessions.filterNot { it.id == id }; detail = null }
        "session_snapshot", "session_state", "session_upsert" -> {
            val runtime = frame.optJSONObject("runtime")
            val session = frame.getJSONObject("session").session(runtime)
            require(session.id == id && session.hostId == hostId)
            sessions = sessions.filterNot { it.id == id } + session
            if (type == "session_snapshot") {
                val firstPage = frame.optJSONObject("history")?.let(::historyPageState)
                loadHistory = frame.getBoolean("hasHistory") && firstPage == null
                detail = SessionDetail(session = session, model = runtime?.optJSONObject("model")?.modelInfo(),
                    snapshotToken = UUID.randomUUID().toString(),
                    operations = frame.getJSONArray("operations").objects().map { it.receipt() },
                    liveItems = frame.getJSONArray("timeline").objects().map { it.item() },
                    savedHistory = firstPage ?: if (loadHistory) SavedHistory.Loading else SavedHistory.None)
            } else if (before != null && before.snapshotToken != null) {
                val generationChanged = before.session.generation != session.generation
                loadHistory = frame.optBoolean("hasHistory") &&
                    (generationChanged || before.savedHistory == SavedHistory.None)
                detail = before.copy(session = session, model = runtime?.optJSONObject("model")?.modelInfo(),
                    historyEpoch = before.historyEpoch + if (generationChanged) 1 else 0,
                    liveItems = if (generationChanged) emptyList() else before.liveItems,
                    savedHistory = if (loadHistory) SavedHistory.Loading else before.savedHistory)
            }
        }
        "timeline" -> if (before?.snapshotToken != null) {
            val removed = frame.getJSONArray("remove").strings().toSet()
            val live = (if (frame.optBoolean("reset")) emptyList() else before.liveItems)
                .filterNot { it.id in removed }.toMutableList()
            val upserts = frame.getJSONArray("upsert")
            // A single streaming replacement needs no index allocation. Large batches avoid
            // scanning the entire transcript for every upsert, preserving the first-match rule.
            val indexes = if (upserts.length() > 1) mutableMapOf<String, Int>().apply {
                live.forEachIndexed { index, item -> putIfAbsent(item.id, index) }
            } else null
            upserts.objects().map { it.item() }.forEach { item ->
                val index = if (indexes != null) indexes[item.id]
                    else live.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }
                if (index != null) live[index] = item else {
                    indexes?.put(item.id, live.size)
                    live.add(item)
                }
            }
            detail = before.copy(liveItems = live)
        }
        "operation" -> if (before?.snapshotToken != null) {
            val operation = frame.getJSONObject("operation").receipt()
            detail = before.copy(operations = before.operations.filterNot { it.commandId == operation.commandId } + operation)
        }
        else -> error("Unsupported protocol event: $type")
    }
    return ProtocolReduction(app.copy(
        hosts = app.hosts + (hostId to host.copy(sessions = sessions, revision = host.revision + 1)),
        details = if (detail == null) app.details - key else app.details + (key to detail),
    ), if (loadHistory) listOf(GatewayEffect.LoadHistory(key)) else emptyList())
}
