package dev.pinkcollab.data

import kotlinx.coroutines.Dispatchers
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
        val session = JSONObject(api.request(p.url, p.credential, "/api/v2/sessions", "POST", body)).session()
        pendingCreateIds.remove(key, commandId)
        return session
    }

    suspend fun detail(hostId: String, id: String) {
        val key = SessionKey(hostId, id)
        mutable.update { it.copy(details = it.details - key) }
        focus(hostId, id)
        awaitSnapshot(state, "Timed out waiting for the session snapshot") { it.details[key]?.subscriptionId != null }
    }

    suspend fun loadHistory(hostId: String, id: String, subscriptionId: String?) {
        val key = SessionKey(hostId, id)
        val before = state.value.details[key] ?: return
        if (before.subscriptionId != subscriptionId) return
        if (subscriptionId == null) {
            failUnstartedHistory(key)
            throw IOException("History unavailable")
        }
        val request = HistoryRequest(subscriptionId, before.historyEpoch)
        val p = paired(hostId)
        val page = try {
            parseHistoryPage(api.request(p.url, p.credential, "/api/v2/sessions/$id/history", query = "limit" to "100"))
        } catch (failure: Exception) {
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

    /** The effect was dropped before a request existed. Do not leave the page Loading. */
    private fun failUnstartedHistory(key: SessionKey) {
        mutable.update { app ->
            val current = app.details[key] ?: return@update app
            if (current.subscriptionId != null || current.savedHistory !is SavedHistory.Loading) return@update app
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
        val request = HistoryRequest(before.subscriptionId ?: return, before.historyEpoch)
        val p = paired(hostId)
        val page = try {
            parseHistoryPage(api.request(p.url, p.credential, "/api/v2/sessions/$id/history", query = "cursor" to cursor))
        } catch (failure: IOException) {
            if ((failure as? GatewayHttpException)?.errorCode == "stale_cursor") {
                before.subscriptionId?.let { loadHistory(hostId, id, it) }
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
        val raw = JSONObject(api.request(p.url, p.credential, "/api/v2/sessions/$id/models"))
        return ModelCatalog(raw.getJSONArray("models").objects().map { it.modelInfo() }, raw.getJSONArray("thinkingLevels").strings())
    }
}

internal fun historyCursor(page: JSONObject): String? = (page.opt("nextCursor") as? String)?.takeIf { it.isNotBlank() }

private data class HistoryPage(val source: String?, val items: List<TimelineItem>, val nextCursor: String?)

internal data class HistoryRequest(val subscriptionId: String, val epoch: Long) {
    fun matches(detail: SessionDetail?): Boolean = detail?.subscriptionId == subscriptionId && detail.historyEpoch == epoch
}

private suspend fun parseHistoryPage(raw: String): HistoryPage = withContext(Dispatchers.Default) {
    val page = JSONObject(raw)
    HistoryPage(
        source = page.optJSONObject("source")?.getString("id"),
        items = page.getJSONArray("items").objects().map { it.item() },
        nextCursor = historyCursor(page),
    )
}
