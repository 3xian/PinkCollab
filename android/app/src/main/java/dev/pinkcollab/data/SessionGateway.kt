package dev.pinkcollab.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Owns session creation, subscriptions, history pagination, and model reads. */
internal class SessionGateway(
    private val mutable: MutableStateFlow<AppState>,
    private val api: GatewayTransport,
    private val paired: (String) -> PairedHost,
    private val focus: (String, String) -> Unit,
) {
    private val state = mutable.asStateFlow()
    private val pendingCreateIds = ConcurrentHashMap<String, String>()

    suspend fun create(hostId: String, cwd: String): Session {
        val p = paired(hostId)
        val key = "$hostId:$cwd"
        val commandId = pendingCreateIds.computeIfAbsent(key) { UUID.randomUUID().toString() }
        val body = JSONObject().put("commandId", commandId).put("hostId", hostId).put("cwd", cwd)
        val session = JSONObject(api.request(p.url, p.credential, "/api/v4/sessions", "POST", body)).session()
        pendingCreateIds.remove(key, commandId)
        return session
    }

    suspend fun detail(hostId: String, id: String) {
        val key = SessionKey(hostId, id)
        invalidateSubscription(hostId, id)
        focus(hostId, id)
        awaitSnapshot(state, "Timed out waiting for the session snapshot") { it.details[key]?.snapshotToken != null }
    }

    /** Release subscription-owned work without evicting the last visible session or transcript. */
    fun invalidateSubscription(hostId: String, id: String) {
        val key = SessionKey(hostId, id)
        mutable.update { app ->
            val current = app.details[key] ?: return@update app
            // A new subscription will resume history loading. Losing the old one is not a history failure.
            app.copy(details = app.details + (key to current.copy(snapshotToken = null)))
        }
    }

    suspend fun loadHistory(hostId: String, id: String, snapshotToken: String?) {
        val key = SessionKey(hostId, id)
        val before = state.value.details[key] ?: return
        if (before.snapshotToken != snapshotToken) return
        if (snapshotToken == null) return
        val request = updateAtomically(mutable) { app ->
            val current = app.details[key]
            if (current?.snapshotToken != snapshotToken) app to null
            else {
                val next = current.copy(historyEpoch = current.historyEpoch + 1)
                app.copy(details = app.details + (key to next)) to HistoryRequest(snapshotToken, next.historyEpoch)
            }
        } ?: return
        val p = paired(hostId)
        val page = try {
            val raw = withTimeoutOrNull(15_000) { firstHistoryPage(key, request, p) }
            if (raw == null) {
                if (!request.matches(state.value.details[key])) return
                throw IOException("Message history took too long to load; try again")
            }
            parseHistoryPage(raw)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            // A visible page stays visible. An in-flight first page must not become "no messages".
            updateMatched(key, request) { current ->
                if (current.savedHistory is SavedHistory.Ready) current else current.copy(savedHistory = SavedHistory.Failed)
            }
            throw failure
        }
        updateMatched(key, request) { current ->
            val previous = current.savedHistory as? SavedHistory.Ready
            val available = conversationTimeline(previous?.items.orEmpty(), current.liveItems)
            val acknowledged = page.confirmed.mapNotNull { confirmation ->
                available.find { it.id == confirmation.id && textHash(it.text) == confirmation.textHash }?.let { confirmation.item.copy(id = it.id, text = it.text) }
            }
            val order = page.order.withIndex().associate { it.value to it.index }
            val incoming = (page.items + acknowledged).sortedBy { order[it.sourceId ?: it.id] ?: Int.MAX_VALUE }
            val retained = if (page.continues && previous != null) {
                val oldItems = conversationTimeline(previous.items, current.liveItems.filter { it.detail != "streaming" && (it.tool == null || it.tool.completed) })
                val changed = incoming.associateBy { it.sourceId ?: it.id }
                val updated = oldItems.map { old -> changed[old.sourceId ?: old.id]?.copy(id = old.id) ?: old }
                val originalIds = oldItems.associate { (it.sourceId ?: it.id) to it.id }
                conversationTimeline(updated, incoming.map { it.copy(id = originalIds[it.sourceId ?: it.id] ?: it.id) })
            } else incoming
            val branchChanged = previous?.branchLeaf != null && !page.continues
            val pageSources = page.items.mapTo(mutableSetOf()) { it.sourceId ?: it.id }
            current.copy(
                savedHistory = page.copy(items = retained, confirmed = emptyList()),
                liveItems = if (!current.session.runtimeAttached) emptyList() else if (branchChanged)
                    current.liveItems.filter { it.detail == "streaming" || it.tool?.completed == false || (it.sourceId ?: it.id) in pageSources }
                    else current.liveItems,
            )
        }
        if (page.syncCursor != null && request.matches(state.value.details[key])) loadHistory(hostId, id, snapshotToken)
    }

    private suspend fun firstHistoryPage(key: SessionKey, request: HistoryRequest, p: PairedHost): String? {
        var retries = 0
        while (true) {
            val current = state.value.details[key] ?: return null
            if (!request.matches(current)) return null
            try {
                val ready = current.savedHistory as? SavedHistory.Ready
                val anchor = ready?.branchLeaf
                val oldest = ready?.items?.firstOrNull()?.let { it.sourceId ?: it.id }
                if (anchor != null) {
                    val candidates = ready.items.takeLast(256) + ready.items.filter { it.tool?.completed == false } + current.liveItems
                    val known = org.json.JSONArray()
                    candidates.distinctBy { it.sourceId ?: it.id }.takeLast(512).forEach { item ->
                        known.put(JSONObject().put("id", item.id).put("sourceId", item.sourceId ?: item.id)
                            .put("textHash", textHash(item.text)).put("version", item.tool?.detailsVersion))
                    }
                    val body = JSONObject().put("anchor", anchor).put("oldest", oldest).put("known", known).put("cursor", ready.syncCursor)
                    return api.request(p.url, p.credential, "/api/v4/sessions/${key.sessionId}/history/sync", "POST", body)
                }
                return api.request(p.url, p.credential, "/api/v4/sessions/${key.sessionId}/history", query = "limit" to InitialHistoryPageSize.toString())
            } catch (failure: GatewayHttpException) {
                if (failure.errorCode !in setOf("history_unavailable", "stale_cursor")) throw failure
                val latest = state.value.details[key] ?: return null
                if (!request.matches(latest)) return null
                // First-prompt metadata may arrive before OMP creates its transcript.
                // Wait for that turn to settle, without treating missing data as empty history.
                if (latest.savedHistory.sourceId == null && latest.session.isActive &&
                    latest.session.status != SessionStatus.Idle) {
                    state.first { app ->
                        val detail = app.details[key]
                        detail == null || !request.matches(detail) || !detail.session.isActive ||
                            detail.session.status == SessionStatus.Idle
                    }
                    continue
                }
                if (failure.errorCode == "stale_cursor" && readySyncCursor(key) != null) {
                    updateMatched(key, request) { detail -> detail.copy(savedHistory = (detail.savedHistory as SavedHistory.Ready).copy(syncCursor = null)) }
                }
                if (retries++ >= 2) throw failure
                delay(250)
            }
        }
    }

    private fun readySyncCursor(key: SessionKey) = (state.value.details[key]?.savedHistory as? SavedHistory.Ready)?.syncCursor

    private fun updateMatched(key: SessionKey, request: HistoryRequest, transform: (SessionDetail) -> SessionDetail) {
        mutable.update { app ->
            val current = app.details[key] ?: return@update app
            if (!request.matches(current)) return@update app
            app.copy(details = app.details + (key to transform(current)))
        }
    }

    suspend fun loadEarlierHistory(hostId: String, id: String) {
        val key = SessionKey(hostId, id)
        val before = state.value.details[key] ?: return
        val ready = before.savedHistory as? SavedHistory.Ready ?: return
        val cursor = ready.nextCursor ?: return
        val request = HistoryRequest(before.snapshotToken ?: return, before.historyEpoch)
        val p = paired(hostId)
        val page = try {
            parseHistoryPage(api.request(p.url, p.credential, "/api/v4/sessions/$id/history", query = "cursor" to cursor))
        } catch (failure: IOException) {
            if ((failure as? GatewayHttpException)?.errorCode == "stale_cursor") {
                before.snapshotToken?.let { loadHistory(hostId, id, it) }
                return
            }
            throw failure
        }
        updateMatched(key, request) { current ->
            val currentReady = current.savedHistory as? SavedHistory.Ready
            if (currentReady == null || currentReady.nextCursor != cursor) current
            else current.copy(savedHistory = currentReady.copy(
                items = conversationTimeline(page.items, currentReady.items),
                nextCursor = page.nextCursor,
            ))
        }
    }

    suspend fun usage(hostId: String): UsageSnapshot {
        val p = paired(hostId)
        val response = api.request(p.url, p.credential, "/api/v4/usage")
        return withContext(Dispatchers.Default) { parseUsage(JSONObject(response)) }
    }

    suspend fun models(hostId: String, id: String): ModelCatalog {
        val p = paired(hostId)
        val response = api.request(p.url, p.credential, "/api/v4/sessions/$id/models")
        return withContext(Dispatchers.Default) {
            val raw = JSONObject(response)
            ModelCatalog(raw.getJSONArray("models").objects().map { it.modelInfo() }, raw.getJSONArray("thinkingLevels").strings())
        }
    }

    suspend fun toolDetails(hostId: String, id: String, callId: String, cursor: String?): ToolDetailPage {
        val p = paired(hostId)
        val callPath = encodeGatewayComponent(callId)
        val raw = JSONObject(api.request(p.url, p.credential, "/api/v4/sessions/$id/tools/$callPath",
            query = cursor?.let { "cursor" to it }))
        return ToolDetailPage(raw.getString("text"), raw.getString("version"),
            raw.opt("nextCursor") as? String, raw.getBoolean("completed"), raw.opt("resumeCursor") as? String, raw.optBoolean("hasMore"))
    }
}

internal fun historyCursor(page: JSONObject): String? = (page.opt("nextCursor") as? String)?.takeIf { it.isNotBlank() }
private fun encodeGatewayComponent(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

internal data class HistoryRequest(val snapshotToken: String, val epoch: Long) {
    fun matches(detail: SessionDetail?): Boolean = detail?.snapshotToken == snapshotToken && detail.historyEpoch == epoch
}

private suspend fun parseHistoryPage(raw: String): SavedHistory.Ready = withContext(Dispatchers.Default) {
    val value = JSONObject(raw)
    historyPageState(value)
}

internal fun historyPageState(page: JSONObject, previousPlans: Map<String, List<TodoPhase>> = emptyMap()): SavedHistory.Ready {
    val plans = readTodoPlans(page, previousPlans)
    return SavedHistory.Ready(
        sourceId = page.optJSONObject("source")?.getString("id"),
        items = page.getJSONArray("items").objects().map { it.item(plans) },
        nextCursor = historyCursor(page),
        branchLeaf = page.optJSONObject("source")?.optString("branchLeaf")?.takeIf(String::isNotBlank),
        continues = page.optJSONObject("source")?.optBoolean("continues") ?: false,
        syncCursor = page.opt("syncCursor") as? String,
        order = page.optJSONArray("order")?.strings().orEmpty(),
        confirmed = page.optJSONArray("confirmed")?.objects()?.map { HistoryConfirmation(it.getString("id"), it.getJSONObject("item").item(plans), it.getString("textHash")) }.orEmpty(),
    )
}

internal fun textHash(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
