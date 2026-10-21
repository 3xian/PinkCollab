package dev.pinkcollab.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Cursor reads cannot overwrite newer socket runtime state or a replacement host snapshot. */
internal class SessionCatalog(private val state: MutableStateFlow<AppState>, private val api: GatewayTransport) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun loadMore(hostId: String) {
        val lock = locks.computeIfAbsent(hostId) { Mutex() }
        if (!lock.tryLock()) return
        try {
            val before = state.value.hosts[hostId] ?: return
            val cursor = before.nextSessionsCursor ?: return
            val page = JSONObject(api.request(before.paired.url, before.paired.credential, "/api/v4/sessions", query = "cursor" to cursor))
            val loaded = page.getJSONArray("sessions").objects().map { it.sessionSummary() }
            state.update { app ->
                val current = app.hosts[hostId] ?: return@update app
                if (current.paired != before.paired || current.snapshotToken != before.snapshotToken ||
                    current.nextSessionsCursor != cursor ||
                    current.serverEpoch != before.serverEpoch || current.serverEpoch != page.opt("epoch")) return@update app
                val known = current.sessions.mapTo(mutableSetOf()) { it.id }
                val pageCatalog = (page.opt("catalogVersion") as? String)?.takeIf { it.isNotBlank() }
                app.copy(hosts = app.hosts + (hostId to current.copy(
                    sessions = current.sessions + loaded.filter { it.id !in known && it.id !in current.removedSessionIds },
                    pagedSessionIds = current.pagedSessionIds + loaded.filter { it.id !in current.removedSessionIds }.map { it.id },
                    pagesCatalogVersion = if (current.pagedSessionIds.isEmpty()) pageCatalog
                        else current.pagesCatalogVersion?.takeIf { it == pageCatalog },
                    nextSessionsCursor = page.opt("nextCursor") as? String,
                )))
            }
        } finally { lock.unlock() }
    }

    suspend fun ensureListed(key: SessionKey) {
        val before = state.value.hosts[key.hostId] ?: return
        if (before.sessions.any { it.id == key.sessionId }) return
        val detail = JSONObject(api.request(before.paired.url, before.paired.credential, "/api/v4/sessions/${key.sessionId}"))
        val session = detail.getJSONObject("session").session(detail.optJSONObject("runtime"))
        state.update { app ->
            val current = app.hosts[key.hostId] ?: return@update app
            if (current.paired != before.paired || current.snapshotToken != before.snapshotToken ||
                current.serverEpoch != before.serverEpoch ||
                key.sessionId in current.removedSessionIds || current.sessions.any { it.id == key.sessionId }) return@update app
            app.copy(hosts = app.hosts + (key.hostId to current.copy(sessions = current.sessions + session)))
        }
    }
}
