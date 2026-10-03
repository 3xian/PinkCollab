package dev.pinkcollab.data

import org.json.JSONObject
import java.util.UUID

internal data class ProtocolReduction(val state: AppState, val effects: List<GatewayEffect> = emptyList())
internal sealed interface GatewayEffect {
    data class LoadHistory(val session: SessionKey) : GatewayEffect
    data class ResyncSession(val session: SessionKey) : GatewayEffect
}

/** Ordered socket events replace small objects; only the live timeline is incremental. */
internal fun reduceProtocol(app: AppState, hostId: String, frame: JSONObject, nowEpochMillis: Long): ProtocolReduction {
    val host = app.hosts[hostId] ?: return ProtocolReduction(app)
    val type = frame.getString("type")
    val epoch = frame.opt("epoch") as? String
    val sequence = frame.optLong("sequence")
    val plans = readTodoPlans(frame, host.todoPlans)
    if (type == "host_sync") {
        require(frame.getInt("protocolVersion") == 4 && epoch == host.serverEpoch)
        return ProtocolReduction(app.copy(hosts = app.hosts + (hostId to host.copy(
            connection = ConnectionState.Online(nowEpochMillis), connectionProgress = null,
            snapshotToken = UUID.randomUUID().toString(), revision = host.revision + 1,
            catalogVersion = frame.opt("catalogVersion") as? String ?: host.catalogVersion,
            totalSessions = frame.optInt("totalSessions", host.totalSessions ?: host.sessions.size),
            wireSequence = sequence, lastSyncedAtEpochMillis = nowEpochMillis, initialSync = InitialSyncState.Ready, todoPlans = plans,
        ))))
    }
    if (type == "host_snapshot") {
        require(frame.getInt("protocolVersion") == 4) { "Upgrade PinkCollab to connect to this Gateway" }
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
                serverEpoch = epoch, wireSequence = sequence, todoPlans = plans,
                totalSessions = frame.optInt("totalSessions", frame.getJSONArray("sessions").length()),
                catalogVersion = frame.opt("catalogVersion") as? String,
                nextSessionsCursor = frame.opt("nextSessionsCursor") as? String,
            )), details = app.details.mapValues { (key, detail) ->
                if (key.hostId == hostId && (epoch == null || epoch != host.serverEpoch || !host.connected)) detail.copy(snapshotToken = null) else detail
            },
        ))
    }
    val id = frame.optString("sessionId").ifBlank { frame.getJSONObject("session").getString("id") }
    val key = SessionKey(hostId, id)
    val before = app.details[key]
    if (type == "session_resume") {
        if (before == null || before.serverEpoch != epoch) return ProtocolReduction(app, listOf(GatewayEffect.ResyncSession(key)))
        return ProtocolReduction(app.copy(details = app.details + (key to before.copy(snapshotToken = UUID.randomUUID().toString()))))
    }
    val hostEvent = type in setOf("session_state", "session_upsert", "runtime_state", "session_remove")
    val replacement = type in setOf("session_snapshot", "session_sync")
    val sequenced = sequence > 0 && epoch == host.serverEpoch
    val updateHost = !sequenced || sequence > host.wireSequence
    val updateDetail = replacement || (before?.snapshotToken != null &&
        (!sequenced || before.serverEpoch != epoch || sequence > before.wireSequence))
    if (!replacement && !(hostEvent && updateHost) && !updateDetail) {
        // A skipped replay may still introduce a dictionary entry referenced by later frames.
        return ProtocolReduction(if (plans == host.todoPlans) app else app.copy(
            hosts = app.hosts + (hostId to host.copy(todoPlans = plans)),
        ))
    }
    var detail = before
    var sessions = host.sessions
    var loadHistory = false
    var resync = false
    when (type) {
        "session_remove" -> {
            if (updateHost) sessions = sessions.filterNot { it.id == id }
            if (updateDetail) detail = null
        }
        "session_snapshot", "session_sync", "session_state", "session_upsert", "runtime_state" -> {
            val runtime = frame.optJSONObject("runtime")
            val record = frame.optJSONObject("session") ?: host.sessions.find { it.id == id }?.wireRecord() ?: error("Missing session metadata")
            val session = record.session(runtime)
            require(session.id == id && session.hostId == hostId)
            if (updateHost) sessions = sessions.filterNot { it.id == id } + session
            if (type == "session_snapshot") {
                val firstPage = frame.optJSONObject("history")?.let { historyPageState(it, plans) }
                val retained = before?.savedHistory as? SavedHistory.Ready
                loadHistory = frame.getBoolean("hasHistory") && (firstPage == null || retained != null)
                detail = SessionDetail(session = session, model = runtime?.optJSONObject("model")?.modelInfo(),
                    snapshotToken = UUID.randomUUID().toString(),
                    operations = frame.getJSONArray("operations").objects().map { it.receipt() },
                    liveItems = frame.getJSONArray("timeline").objects().map { it.item(plans) },
                    savedHistory = if (loadHistory && retained != null) retained
                        else firstPage ?: if (loadHistory) SavedHistory.Loading else SavedHistory.None,
                    serverEpoch = epoch, wireSequence = sequence)
            } else if (updateDetail && before != null) {
                val generationChanged = before.session.generation != session.generation
                loadHistory = frame.optBoolean("hasHistory") &&
                    (type == "session_sync" || generationChanged || before.savedHistory == SavedHistory.None ||
                        (before.session.status in setOf(SessionStatus.Running, SessionStatus.NeedsInput) && session.status == SessionStatus.Idle))
                val oldHistory = before.savedHistory as? SavedHistory.Ready
                val retainedHistory = if (generationChanged && oldHistory != null) oldHistory.copy(
                    items = conversationTimeline(oldHistory.items, before.liveItems.filter {
                        it.detail != "streaming" && (it.tool == null || it.tool.completed)
                    }),
                ) else before.savedHistory
                detail = before.copy(session = session, model = runtime?.optJSONObject("model")?.modelInfo(),
                    historyEpoch = before.historyEpoch + if (generationChanged) 1 else 0,
                    liveItems = if (generationChanged) emptyList() else before.liveItems,
                    savedHistory = if (loadHistory && retainedHistory !is SavedHistory.Ready) SavedHistory.Loading else retainedHistory)
            }
        }
        "message_patch" -> if (before?.snapshotToken != null) {
            val messageId = frame.getString("id")
            val existing = before.liveItems.find { it.id == messageId }
            val expected = frame.getString("baseHash")
            val target = frame.getString("hash")
            val currentHash = existing?.let { textHash(it.text) }
            if (existing == null || (currentHash != expected && currentHash != target)) resync = true
            else if (currentHash == expected) {
                val text = existing.text + frame.getString("append")
                if (textHash(text) != target) resync = true
                else {
                    val item = frame.optJSONObject("metadata")?.item(plans)?.copy(text = text) ?: existing.copy(text = text)
                    detail = before.copy(liveItems = before.liveItems.map { if (it.id == messageId) item else it })
                }
            } else frame.optJSONObject("metadata")?.let { metadata ->
                detail = before.copy(liveItems = before.liveItems.map { if (it.id == messageId) metadata.item(plans).copy(text = existing.text) else it })
            }
        }
        "timeline" -> if (before?.snapshotToken != null) {
            val removed = frame.getJSONArray("remove").strings().toSet()
            val retired = before.liveItems.filter { (it.id in removed || frame.optBoolean("reset")) &&
                it.detail != "streaming" && (it.tool == null || it.tool.completed) }
            val live = (if (frame.optBoolean("reset")) emptyList() else before.liveItems)
                .filterNot { it.id in removed }.toMutableList()
            val upserts = frame.getJSONArray("upsert")
            // A single streaming replacement needs no index allocation. Large batches avoid
            // scanning the entire transcript for every upsert, preserving the first-match rule.
            val indexes = if (upserts.length() > 1) mutableMapOf<String, Int>().apply {
                live.forEachIndexed { index, item -> putIfAbsent(item.id, index) }
            } else null
            upserts.objects().map { it.item(plans) }.forEach { item ->
                val index = if (indexes != null) indexes[item.id]
                    else live.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }
                if (index != null) live[index] = item else {
                    indexes?.put(item.id, live.size)
                    live.add(item)
                }
            }
            val ready = before.savedHistory as? SavedHistory.Ready
            detail = before.copy(liveItems = live, savedHistory = if (ready != null && retired.isNotEmpty())
                ready.copy(items = conversationTimeline(ready.items, retired)) else before.savedHistory)
            if (frame.optBoolean("reset") && before.savedHistory != SavedHistory.None) loadHistory = true
        }
        "operation" -> if (before?.snapshotToken != null) {
            val operation = frame.getJSONObject("operation").receipt()
            detail = before.copy(operations = before.operations.filterNot { it.commandId == operation.commandId } + operation)
        }
        else -> error("Unsupported protocol event: $type")
    }
    return ProtocolReduction(app.copy(
        hosts = app.hosts + (hostId to host.copy(sessions = sessions, revision = host.revision + 1,
            catalogVersion = if (hostEvent && updateHost) frame.opt("catalogVersion") as? String ?: host.catalogVersion else host.catalogVersion,
            totalSessions = if (hostEvent && updateHost && frame.has("totalSessions")) frame.getInt("totalSessions") else host.totalSessions,
            wireSequence = if (hostEvent && updateHost) maxOf(host.wireSequence, sequence) else host.wireSequence, todoPlans = plans)),
        details = if (detail == null) app.details - key else app.details + (key to if (updateDetail && detail.snapshotToken != null)
            detail.copy(serverEpoch = epoch ?: detail.serverEpoch, wireSequence = maxOf(detail.wireSequence, sequence)) else detail),
    ), if (resync) listOf(GatewayEffect.ResyncSession(key)) else if (loadHistory) listOf(GatewayEffect.LoadHistory(key)) else emptyList())
}
