package dev.pinkcollab.data

import org.json.JSONObject

internal data class V2Reduction(
    val state: AppState,
    val resyncResource: String? = null,
    val historySessionId: String? = null,
)

/** Only cursor-valid snapshots and changes may modify a live resource. */
internal fun reduceV2(app: AppState, hostId: String, frame: JSONObject): V2Reduction {
    val host = app.hosts[hostId] ?: return V2Reduction(app)
    val resource = frame.optString("resource")
    return when (frame.optString("type")) {
        "snapshot" -> {
            val payload = frame.getJSONObject("payload")
            val cursor = frame.getJSONObject("cursor").cursor()
            val subscriptionId = frame.getString("subscriptionId")
            if (resource == "host/sessions") {
                require(payload.getInt("protocolVersion") == 2) { "Upgrade PinkCollab to connect to this Gateway" }
                val identity = payload.getJSONObject("host").host()
                require(identity.id == hostId) { "Gateway identity changed; pair again" }
                val sessions = payload.getJSONArray("sessions").objects().map { it.sessionSummary() }
                val workspaces = payload.getJSONArray("workspaces").objects().map { it.workspace() }
                V2Reduction(app.copy(
                    hosts = app.hosts + (hostId to host.copy(
                        paired = host.paired.copy(host = identity),
                        connection = ConnectionState.Online(System.currentTimeMillis()),
                        sessions = sessions,
                        workspaces = workspaces,
                        cursor = cursor,
                        subscriptionId = subscriptionId,
                        revision = host.revision + 1,
                        lastSyncedAtEpochMillis = System.currentTimeMillis(),
                        initialSync = InitialSyncState.Ready,
                    )),
                    details = app.details.filterValues { it.session.hostId != hostId },
                ))
            } else {
                val sessionId = resource.removePrefix("session/")
                require(resource.startsWith("session/") && payload.getJSONObject("session").getString("id") == sessionId)
                val runtime = payload.optJSONObject("runtime")
                val session = payload.getJSONObject("session").session(runtime)
                val live = payload.getJSONArray("messages").objects().map { it.item() }
                // A fresh subscription has no source proof for a page cached by the previous one.
                val history = emptyList<TimelineItem>()
                val receipt = payload.optJSONArray("recentOperations")?.objects()?.map { it.receipt() }.orEmpty()
                val detail = SessionDetail(
                    session = session,
                    timeline = history + live,
                    model = runtime?.optJSONObject("actualModel")?.modelInfo(),
                    cursor = cursor,
                    subscriptionId = subscriptionId,
                    operations = receipt,
                    historySourceId = null,
                    nextHistoryCursor = null,
                    historyItems = history,
                    liveItems = live,
                )
                val updatedHost = host.copy(sessions = host.sessions.filterNot { it.id == sessionId } + session, revision = host.revision + 1)
                V2Reduction(app.copy(hosts = app.hosts + (hostId to updatedHost), details = app.details + (sessionId to detail)),
                    historySessionId = if (runtime == null && payload.optString("historyRef").isNotBlank()) sessionId else null)
            }
        }
        "change" -> {
            val epoch = frame.getString("epoch")
            val base = frame.getLong("baseRevision")
            val revision = frame.getLong("revision")
            val subscriptionId = frame.getString("subscriptionId")
            if (resource == "host/sessions") {
                val cursor = host.cursor ?: return V2Reduction(app, resource)
                if (host.subscriptionId != subscriptionId || cursor.epoch != epoch) return V2Reduction(app, resource)
                if (revision <= cursor.revision) return V2Reduction(app)
                if (!cursor.accepts(epoch, base, revision)) return V2Reduction(app, resource)
                var sessions = host.sessions
                frame.getJSONArray("changes").objects().forEach { change ->
                    when (change.optString("type")) {
                        "session.created" -> {
                            val session = change.getJSONObject("value").session()
                            sessions = sessions.filterNot { it.id == session.id } + session
                        }
                        "summary.changed" -> {
                            val id = change.getString("sessionId")
                            val current = sessions.firstOrNull { it.id == id } ?: return V2Reduction(app, resource)
                            val updated = applyRuntimeChange(current, change.optString("changeKind"), change.getJSONObject("value"))
                            sessions = sessions.filterNot { it.id == id } + updated
                        }
                    }
                }
                V2Reduction(app.copy(hosts = app.hosts + (hostId to host.copy(sessions = sessions, cursor = cursor.copy(revision = revision), revision = host.revision + 1))))
            } else {
                val sessionId = resource.removePrefix("session/")
                val detail = app.details[sessionId] ?: return V2Reduction(app, resource)
                val cursor = detail.cursor ?: return V2Reduction(app, resource)
                if (detail.subscriptionId != subscriptionId || cursor.epoch != epoch) return V2Reduction(app, resource)
                if (revision <= cursor.revision) return V2Reduction(app)
                if (!cursor.accepts(epoch, base, revision)) return V2Reduction(app, resource)
                var updated = detail
                var loadHistory = false
                frame.getJSONArray("changes").objects().forEach { change ->
                    val value = change.getJSONObject("value")
                    when (change.getString("type")) {
                        "v2.session.changed" -> {
                            val runtime = value.optJSONObject("runtime")
                            val live = value.getJSONArray("messages").objects().map { it.item() }
                            updated = updated.copy(
                                session = updated.session.withRuntime(runtime),
                                timeline = updated.historyItems + live,
                                liveItems = live,
                                model = runtime?.optJSONObject("actualModel")?.modelInfo(),
                            )
                        }
                        "v2.runtime.updated" -> updated = updated.copy(
                            session = applyRuntimeChange(updated.session, "v2.runtime.updated", value),
                            model = value.optJSONObject("runtime")?.optJSONObject("actualModel")?.modelInfo(),
                        )
                        "v2.metadata.updated" -> updated = updated.copy(
                            session = updated.session.withMetadata(value.getJSONObject("session")),
                        )
                        "v2.runtime.exited" -> {
                            updated = updated.copy(session = updated.session.withRuntime(null), liveItems = emptyList())
                            loadHistory = true
                        }
                        "v2.operation.updated" -> {
                            val receipt = value.receipt()
                            updated = updated.copy(operations = updated.operations.filterNot { it.commandId == receipt.commandId } + receipt)
                        }
                    }
                }
                updated = updated.copy(cursor = cursor.copy(revision = revision))
                val updatedHost = host.copy(sessions = host.sessions.filterNot { it.id == sessionId } + updated.session, revision = host.revision + 1)
                V2Reduction(app.copy(hosts = app.hosts + (hostId to updatedHost), details = app.details + (sessionId to updated)), historySessionId = if (loadHistory) sessionId else null)
            }
        }
        "resync_required" -> V2Reduction(app, resource.ifBlank { "host/sessions" })
        else -> V2Reduction(app)
    }
}

private fun applyRuntimeChange(session: Session, kind: String, value: JSONObject): Session {
    if (kind == "v2.metadata.updated") return session.withMetadata(value.getJSONObject("session"))
    if (kind == "v2.runtime.exited") return session.withRuntime(null)
    if (value.has("runtime") && value.isNull("runtime")) return session.withRuntime(null)
    val runtime = value.optJSONObject("runtime") ?: JSONObject()
        .put("generation", value.optString("generation", session.runtimeGeneration ?: ""))
        .put("phase", value.optString("phase", if (session.runtimeAttached) "ready" else "starting"))
        .put("execution", value.optString("execution", session.runtimeExecution))
        .put("pendingInputs", value.optJSONArray("pendingInputs"))
    return session.withRuntime(runtime)
}

private fun Session.withMetadata(record: JSONObject): Session = copy(
    cwd = record.getString("cwd"),
    title = record.getString("title"),
    createdAt = record.getString("createdAt"),
    updatedAt = record.getString("updatedAt"),
)
