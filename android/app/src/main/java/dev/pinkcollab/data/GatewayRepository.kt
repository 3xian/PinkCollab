package dev.pinkcollab.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class GatewayRepository(private val scope: CoroutineScope, private val credentials: CredentialStore) {
    private val api = GatewayApi()
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    private val connections = HostConnectionSupervisor(scope, api, ::connectionState, ::event)
    private val listings = DirectoryListingCache(scope)
    private val initialSyncTimeouts = ConcurrentHashMap<String, Job>()

    init {
        runCatching { credentials.read() }.onSuccess { saved ->
            mutable.update {
                it.copy(
                    hosts = saved.associate { paired -> paired.host.id to HostState(paired) },
                )
            }
            saved.forEach { connect(it) }
        }.onFailure {
            mutable.update { state ->
                state.copy(
                    error = "Pairing data could not be decrypted; pair again: ${it.message}",
                )
            }
        }
    }
    fun error(message: String?) { mutable.update { it.copy(error = message) } }
    private fun paired(id: String) = state.value.hosts[id]?.paired ?: throw IllegalStateException("Host removed")
    suspend fun pair(url: String, token: String) {
        val base = api.validateURL(url)
        val response = JSONObject(api.request(base, null, "/api/v1/pair", "POST", JSONObject().put("token", token.trim()).put("name", "PinkCollab Android")))
        require(response.getInt("protocolVersion") == 1) { "Unsupported Gateway protocol version" }
        val paired = PairedHost(response.getJSONObject("host").host(), base, response.getString("credential"), response.getString("clientId"))
        val hosts = state.value.hosts + (paired.host.id to HostState(paired))
        credentials.save(hosts.values.map { it.paired })
        mutable.update { it.copy(hosts = it.hosts + (paired.host.id to HostState(paired))) }
        connect(paired)
    }
    fun forget(id: String) {
        val hosts = state.value.hosts - id
        credentials.save(hosts.values.map { it.paired })
        connections.forget(id)
        initialSyncTimeouts.remove(id)?.cancel()
        listings.removeHost(id)
        mutable.update { it.copy(hosts = it.hosts - id, details = it.details.filterValues { d -> d.session.hostId != id }) }
    }

    private fun connect(paired: PairedHost) {
        connections.connect(paired)
        scheduleInitialSyncTimeout(paired.host.id)
    }

    private fun scheduleInitialSyncTimeout(hostId: String) {
        if (state.value.hosts[hostId]?.initialSync != InitialSyncState.Pending) return
        while (true) {
            val existing = initialSyncTimeouts[hostId]
            if (existing != null) {
                if (!existing.isCompleted) return
                initialSyncTimeouts.remove(hostId, existing)
                continue
            }

            val timeout = scope.launch(start = CoroutineStart.LAZY) {
                delay(InitialSyncTimeoutMillis)
                mutable.update { app ->
                    val host = app.hosts[hostId] ?: return@update app
                    if (host.initialSync != InitialSyncState.Pending) return@update app
                    app.copy(
                        hosts = app.hosts + (hostId to host.copy(initialSync = InitialSyncState.Unavailable)),
                    )
                }
            }
            if (initialSyncTimeouts.putIfAbsent(hostId, timeout) == null) {
                timeout.invokeOnCompletion { initialSyncTimeouts.remove(hostId, timeout) }
                timeout.start()
                return
            }
            timeout.cancel()
        }
    }

    private fun completeInitialSync(hostId: String) {
        initialSyncTimeouts.remove(hostId)?.cancel()
    }

    private fun connectionState(hostId: String, connection: ConnectionState) {
        mutable.update { app ->
            val host = app.hosts[hostId] ?: return@update app
            val initialSync = when (connection) {
                is ConnectionState.Offline,
                ConnectionState.AuthenticationRequired,
                -> if (host.initialSync == InitialSyncState.Pending) InitialSyncState.Unavailable else host.initialSync

                else -> host.initialSync
            }
            app.copy(hosts = app.hosts + (hostId to host.copy(connection = connection, initialSync = initialSync)))
        }
        if (connection is ConnectionState.Offline || connection == ConnectionState.AuthenticationRequired) {
            completeInitialSync(hostId)
        }
    }

    private fun event(hostId: String, frame: JSONObject) {
        val payload = frame.getJSONObject("payload")
        when (frame.getString("type")) {
            "snapshot" -> {
                require(payload.getInt("protocolVersion") == 1)
                val host = payload.getJSONObject("host").host()
                require(host.id == hostId) { "Gateway identity changed; pair again" }
                val sessions = payload.getJSONArray("sessions").objects().map { it.session() }
                val workspaces = payload.optJSONArray("workspaces")?.objects()?.map { it.workspace() }
                mutable.update { app ->
                    val h = app.hosts[hostId] ?: return@update app
                    val details = app.details.mapValues { (_, d) -> if (d.session.hostId == hostId) d.copy(streaming = "") else d }
                    app.copy(
                        hosts = app.hosts + (hostId to h.copy(
                            paired = h.paired.copy(host = host),
                            connection = ConnectionState.Online(System.currentTimeMillis()),
                            sessions = sessions,
                            workspaces = workspaces ?: h.workspaces,
                            revision = h.revision + 1,
                            lastSyncedAtEpochMillis = System.currentTimeMillis(),
                            initialSync = InitialSyncState.Ready,
                        )),
                        details = details,
                    )
                }
                completeInitialSync(hostId)
                if (workspaces == null) scope.launch { runCatching { refreshHost(hostId) } }
                workspaces?.let { prefetchListings(hostId, it.map(Workspace::path)) }
                scope.launch { state.value.details.values.filter { it.session.hostId == hostId }.forEach { runCatching { detail(hostId, it.session.id) } } }
            }
            "session.updated" -> {
                val s = payload.session()
                mutable.update { app ->
                    val h = app.hosts[hostId] ?: return@update app
                    val old = h.sessions.firstOrNull { it.id == s.id }
                    if (old != null && compareTimestamps(old.updatedAt, s.updatedAt) > 0) return@update app
                    val details = app.details[s.id]?.let { app.details + (s.id to it.copy(session = s, streaming = if (s.status in listOf("completed", "failed", "stopped", "idle", "offline")) "" else it.streaming)) } ?: app.details
                    app.copy(hosts = app.hosts + (hostId to h.copy(sessions = h.sessions.filterNot { it.id == s.id } + s, revision = h.revision + 1)), details = details)
                }
            }
            "timeline.updated" -> {
                val id = payload.getString("sessionId"); val item = payload.getJSONObject("item").item()
                mutable.update { app ->
                    val d = app.details[id] ?: return@update app
                    // The event is the newest state for its id, so it wins a timestamp tie.
                    val merged = mergeTimeline(listOf(item), d.timeline)
                    app.copy(details = app.details + (id to d.copy(timeline = merged, streaming = if (item.kind == "assistant") "" else d.streaming)))
                }
            }
            "message.delta" -> {
                val id = payload.getString("sessionId")
                mutable.update { app ->
                    val d = app.details[id] ?: return@update app
                    app.copy(details = app.details + (id to d.copy(streaming = (d.streaming + payload.getString("text")).takeLast(100000))))
                }
            }
            "model.updated" -> {
                val id = payload.getString("sessionId")
                val model = payload.optJSONObject("model")?.modelInfo()
                mutable.update { app ->
                    val detail = app.details[id] ?: return@update app
                    app.copy(details = app.details + (id to detail.copy(model = model)))
                }
            }
            "session.deleted" -> {
                val id = payload.getString("sessionId")
                mutable.update { app ->
                    val h = app.hosts[hostId] ?: return@update app
                    app.copy(hosts = app.hosts + (hostId to h.copy(sessions = h.sessions.filterNot { it.id == id }, revision = h.revision + 1)), details = app.details - id)
                }
            }
        }
    }
    suspend fun refreshHost(id: String) {
        check(state.value.hosts[id]?.connection is ConnectionState.Online) { "Host is not online" }
        val p = paired(id)
        val revision = state.value.hosts[id]?.revision
        val host = JSONObject(api.request(p.url, p.credential, "/api/v1/host")).host()
        require(host.id == id) { "Gateway identity changed; pair again" }
        val sessions = JSONArray(api.request(p.url, p.credential, "/api/v1/sessions")).objects().map { it.session() }
        val roots = JSONArray(api.request(p.url, p.credential, "/api/v1/workspaces")).objects().map { it.workspace() }
        mutable.update { app ->
            val h = app.hosts[id] ?: return@update app
            if (h.paired.credential != p.credential) return@update app
            app.copy(hosts = app.hosts + (id to h.copy(
                paired = h.paired.copy(host = host),
                sessions = if (h.revision == revision) sessions else h.sessions,
                workspaces = roots,
                lastSyncedAtEpochMillis = System.currentTimeMillis(),
                initialSync = InitialSyncState.Ready,
            )))
        }
        val refreshed = state.value.hosts[id]
        if (refreshed?.paired?.credential == p.credential && refreshed.initialSync == InitialSyncState.Ready) {
            completeInitialSync(id)
        }
    }

    fun requestReconnect(id: String) {
        val paired = state.value.hosts[id]?.paired ?: return
        mutable.update { app ->
            val host = app.hosts[id] ?: return@update app
            if (host.initialSync != InitialSyncState.Unavailable) return@update app
            app.copy(hosts = app.hosts + (id to host.copy(initialSync = InitialSyncState.Pending)))
        }
        connect(paired)
    }

    fun reconnectUnavailableHosts() {
        state.value.hosts.values
            .filter { it.connection is ConnectionState.Reconnecting || it.connection is ConnectionState.Offline }
            .forEach { connect(it.paired) }
    }

    fun networkUnavailable() {
        val affected = state.value.hosts
            .filterValues { it.connection !is ConnectionState.AuthenticationRequired }
            .keys
        connections.networkUnavailable(affected)
    }
    suspend fun listing(hostId: String, path: String, forceRefresh: Boolean = false): Listing {
        val key = DirectoryListingKey(hostId, path)
        return listings.getOrLoad(key, forceRefresh) {
            val p = paired(hostId)
            JSONObject(
                api.request(p.url, p.credential, "/api/v1/fs/list", query = "path" to path),
            ).listing()
        }
    }

    fun prefetchListings(hostId: String, paths: List<String>) {
        paths.distinct().take(12).forEach { path ->
            scope.launch { runCatching { listing(hostId, path) } }
        }
    }
    suspend fun create(hostId: String, cwd: String): Session {
        val p = paired(hostId)
        val body = JSONObject()
            .put("hostId", hostId)
            .put("cwd", cwd)
        val session = JSONObject(
            api.request(p.url, p.credential, "/api/v1/sessions", "POST", body),
        ).session()
        mutable.update { app ->
            val host = app.hosts[hostId] ?: return@update app
            val sessions = host.sessions.filterNot { it.id == session.id } + session
            app.copy(hosts = app.hosts + (hostId to host.copy(sessions = sessions)))
        }
        return session
    }
    suspend fun detail(hostId: String, id: String) {
        val p = paired(hostId)
        val raw = JSONObject(api.request(p.url, p.credential, "/api/v1/sessions/$id"))
        val s = raw.getJSONObject("session").session(); val timeline = raw.getJSONArray("timeline").objects().map { it.item() }
        mutable.update { app ->
            val old = app.details[id]
            val current = app.hosts[hostId]?.sessions?.firstOrNull { it.id == id }
            // The fresh snapshot is authoritative for a read; the cached list only fills its gaps.
            val merged = mergeTimeline(timeline, old?.timeline.orEmpty())
            val latest = if (current != null && compareTimestamps(current.updatedAt, s.updatedAt) > 0) current else s
            app.copy(details = app.details + (id to SessionDetail(latest, merged, if (latest.status in listOf("completed", "failed", "stopped", "idle", "offline")) "" else old?.streaming.orEmpty(), raw.optJSONObject("model")?.modelInfo())))
        }
    }
    suspend fun models(hostId: String, id: String): List<ModelInfo> {
        val p = paired(hostId)
        val raw = JSONObject(api.request(p.url, p.credential, "/api/v1/sessions/$id/models"))
        return raw.getJSONArray("models").objects().map { it.modelInfo() }
    }
    suspend fun selectModel(hostId: String, id: String, model: ModelInfo) {
        val p = paired(hostId)
        val raw = JSONObject(
            api.request(
                p.url,
                p.credential,
                "/api/v1/sessions/$id/model",
                "POST",
                JSONObject()
                    .put("provider", model.provider)
                    .put("id", model.id)
                    .put("role", requireNotNull(model.role)),
            ),
        )
        setModel(id, raw.getJSONObject("model").modelInfo())
    }
    private fun setModel(id: String, model: ModelInfo?) {
        mutable.update { app ->
            val detail = app.details[id] ?: return@update app
            app.copy(details = app.details + (id to detail.copy(model = model)))
        }
    }

    suspend fun command(hostId: String, id: String, command: String, body: JSONObject = JSONObject()) {
        val p = paired(hostId); api.request(p.url, p.credential, "/api/v1/sessions/$id/$command", "POST", body); detail(hostId, id)
    }

}
