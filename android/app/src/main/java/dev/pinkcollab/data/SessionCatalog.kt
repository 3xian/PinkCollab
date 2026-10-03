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
                if (current.snapshotToken != before.snapshotToken || current.nextSessionsCursor != cursor || current.serverEpoch != page.opt("epoch")) return@update app
                val known = current.sessions.mapTo(mutableSetOf()) { it.id }
                app.copy(hosts = app.hosts + (hostId to current.copy(
                    sessions = current.sessions + loaded.filter { it.id !in known },
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
            if (current.snapshotToken != before.snapshotToken || current.sessions.any { it.id == key.sessionId }) return@update app
            app.copy(hosts = app.hosts + (key.hostId to current.copy(sessions = current.sessions + session)))
        }
    }
}
