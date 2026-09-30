package dev.pinkcollab.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
        val session = JSONObject(api.request(p.url, p.credential, "/api/v3/sessions", "POST", body)).session()
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
            app.copy(details = app.details + (key to current.copy(
                snapshotToken = null,
                savedHistory = if (current.savedHistory is SavedHistory.Loading) SavedHistory.Failed else current.savedHistory,
            )))
        }
    }

    suspend fun loadHistory(hostId: String, id: String, snapshotToken: String?) {
        val key = SessionKey(hostId, id)
        val before = state.value.details[key] ?: return
        if (before.snapshotToken != snapshotToken) return
        if (snapshotToken == null) {
            failUnstartedHistory(key)
            throw IOException("History unavailable")
        }
        val request = HistoryRequest(snapshotToken, before.historyEpoch)
        val p = paired(hostId)
        val page = try {
            firstHistoryPage(key, request, p) ?: return
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            // A visible page stays visible. An in-flight first page must not become "no messages".
            updateMatched(key, request) { current ->
                if (current.savedHistory is SavedHistory.Ready) current else current.copy(savedHistory = SavedHistory.Failed)
            }
            throw failure
        }
        updateMatched(key, request) { current ->
            current.copy(
                savedHistory = SavedHistory.Ready(page.source, page.items, page.nextCursor),
                liveItems = if (current.session.runtimeAttached) current.liveItems else emptyList(),
            )
        }
    }

    private suspend fun firstHistoryPage(key: SessionKey, request: HistoryRequest, p: PairedHost): HistoryPage? {
        var retries = 0
        while (true) {
            val current = state.value.details[key] ?: return null
            if (!request.matches(current)) return null
            try {
                return parseHistoryPage(api.request(p.url, p.credential,
                    "/api/v3/sessions/${key.sessionId}/history", query = "limit" to "100"))
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
                if (retries++ >= 2) throw failure
                delay(250)
            }
        }
    }

    /** The effect was dropped before a request existed. Do not leave the page Loading. */
    private fun failUnstartedHistory(key: SessionKey) {
        mutable.update { app ->
            val current = app.details[key] ?: return@update app
            if (current.snapshotToken != null || current.savedHistory !is SavedHistory.Loading) return@update app
            app.copy(details = app.details + (key to current.copy(savedHistory = SavedHistory.Failed)))
        }
    }

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
            parseHistoryPage(api.request(p.url, p.credential, "/api/v3/sessions/$id/history", query = "cursor" to cursor))
        } catch (failure: IOException) {
            if ((failure as? GatewayHttpException)?.errorCode == "stale_cursor") {
                before.snapshotToken?.let { loadHistory(hostId, id, it) }
                return
            }
            throw failure
        }
        updateMatched(key, request) { current ->
            val currentReady = current.savedHistory as? SavedHistory.Ready
            if (currentReady == null || currentReady.sourceId != page.source) current
            else current.copy(savedHistory = currentReady.copy(
                items = page.items + currentReady.items,
                nextCursor = page.nextCursor,
            ))
        }
    }

    suspend fun models(hostId: String, id: String): ModelCatalog {
        val p = paired(hostId)
        val response = api.request(p.url, p.credential, "/api/v3/sessions/$id/models")
        return withContext(Dispatchers.Default) {
            val raw = JSONObject(response)
            ModelCatalog(raw.getJSONArray("models").objects().map { it.modelInfo() }, raw.getJSONArray("thinkingLevels").strings())
        }
    }
}

internal fun historyCursor(page: JSONObject): String? = (page.opt("nextCursor") as? String)?.takeIf { it.isNotBlank() }

private data class HistoryPage(val source: String?, val items: List<TimelineItem>, val nextCursor: String?)

internal data class HistoryRequest(val snapshotToken: String, val epoch: Long) {
    fun matches(detail: SessionDetail?): Boolean = detail?.snapshotToken == snapshotToken && detail.historyEpoch == epoch
}

private suspend fun parseHistoryPage(raw: String): HistoryPage = withContext(Dispatchers.Default) {
    val page = JSONObject(raw)
    HistoryPage(
        source = page.optJSONObject("source")?.getString("id"),
        items = page.getJSONArray("items").objects().map { it.item() },
        nextCursor = historyCursor(page),
    )
}
