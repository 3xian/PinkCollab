package dev.pinkcollab.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GatewayRepository(private val scope: CoroutineScope, private val credentials: CredentialStore) {
    private val api = GatewayApi()
    private val mutable = MutableStateFlow(AppState())
    val state = mutable.asStateFlow()
    private val connections = HostConnectionSupervisor(scope, api, ::connectionState, ::event)
    private val listings = DirectoryListingCache(scope)
    private val initialSyncTimeouts = ConcurrentHashMap<String, Job>()
    private val pendingCreateIds = ConcurrentHashMap<String, String>()
    private val pendingCommandIds = ConcurrentHashMap<String, String>()

    init {
        runCatching { credentials.read() }.onSuccess { saved ->
            mutable.update { it.copy(hosts = saved.associate { paired -> paired.host.id to HostState(paired) }) }
            saved.forEach(::connect)
        }.onFailure { error("Pairing data could not be decrypted; pair again: ${it.message}") }
    }

    fun error(message: String?) { mutable.update { it.copy(error = message) } }
    private fun paired(id: String) = state.value.hosts[id]?.paired ?: throw IllegalStateException("Host removed")

    suspend fun pair(url: String, token: String) {
        val base = api.validateURL(url)
        val response = JSONObject(api.request(base, null, "/api/v2/pair", "POST", JSONObject().put("token", token.trim()).put("name", "PinkCollab Android")))
        require(response.getInt("protocolVersion") == 2) { "Upgrade PinkCollab to connect to this Gateway" }
        val paired = PairedHost(response.getJSONObject("host").host(), base, response.getString("credential"), response.getString("clientId"))
        val hosts = state.value.hosts + (paired.host.id to HostState(paired))
        credentials.save(hosts.values.map { it.paired })
        mutable.update { it.copy(hosts = hosts) }
        connect(paired)
    }

    fun forget(id: String) {
        val hosts = state.value.hosts - id
        credentials.save(hosts.values.map { it.paired })
        connections.forget(id)
        initialSyncTimeouts.remove(id)?.cancel()
        listings.removeHost(id)
        mutable.update { it.copy(hosts = hosts, details = it.details.filterValues { detail -> detail.session.hostId != id }) }
    }

    private fun connect(paired: PairedHost) {
        connections.connect(paired)
        val hostId = paired.host.id
        initialSyncTimeouts.remove(hostId)?.cancel()
        initialSyncTimeouts[hostId] = scope.launch {
            delay(InitialSyncTimeoutMillis)
            mutable.update { app ->
                val host = app.hosts[hostId] ?: return@update app
                if (host.initialSync != InitialSyncState.Pending) app else app.copy(hosts = app.hosts + (hostId to host.copy(initialSync = InitialSyncState.Unavailable)))
            }
        }
    }

    private fun connectionState(hostId: String, connection: ConnectionState) {
        mutable.update { app ->
            val host = app.hosts[hostId] ?: return@update app
            val unavailable = connection is ConnectionState.Offline || connection == ConnectionState.AuthenticationRequired || connection == ConnectionState.UpgradeRequired
            val initial = if (unavailable && host.initialSync == InitialSyncState.Pending) InitialSyncState.Unavailable else host.initialSync
            app.copy(hosts = app.hosts + (hostId to host.copy(connection = connection, initialSync = initial)))
        }
        if (connection is ConnectionState.Offline || connection == ConnectionState.AuthenticationRequired || connection == ConnectionState.UpgradeRequired) initialSyncTimeouts.remove(hostId)?.cancel()
    }

    private fun event(hostId: String, frame: JSONObject) {
        val reduction = synchronized(this) {
            val reduced = reduceV2(state.value, hostId, frame)
            mutable.value = reduced.state
            reduced
        }
        if (frame.optString("type") == "snapshot" && frame.optString("resource") == "host/sessions") {
            initialSyncTimeouts.remove(hostId)?.cancel()
        }
        reduction.resyncResource?.let { resource ->
            if (resource == "host/sessions") state.value.hosts[hostId]?.paired?.let(::connect)
            else if (resource.startsWith("session/")) {
                val sessionId = resource.removePrefix("session/")
                if (connections.isDesired(hostId, sessionId)) connections.subscribe(hostId, sessionId)
            }
        }
        reduction.historySessionId?.let { sessionId ->
            val subscriptionId = state.value.details[sessionId]?.subscriptionId
            if (subscriptionId != null) scope.launch {
                runCatching { loadHistory(hostId, sessionId, subscriptionId) }.onFailure { error(it.message ?: "History unavailable") }
            }
        }
    }

    suspend fun refreshHost(id: String) {
        val before = state.value.hosts[id]?.subscriptionId
        connect(paired(id))
        withTimeout(15_000) { state.first { snapshot -> snapshot.hosts[id]?.subscriptionId?.let { it != before } == true } }
    }

    fun requestReconnect(id: String) {
        val host = state.value.hosts[id] ?: return
        mutable.update { app -> app.copy(hosts = app.hosts + (id to host.copy(initialSync = InitialSyncState.Pending))) }
        connect(host.paired)
    }

    fun reconnectUnavailableHosts() {
        state.value.hosts.values.filter { it.connection is ConnectionState.Reconnecting || it.connection is ConnectionState.Offline }.forEach { connect(it.paired) }
    }

    fun networkUnavailable() {
        connections.networkUnavailable(state.value.hosts.filterValues { it.connection != ConnectionState.AuthenticationRequired && it.connection != ConnectionState.UpgradeRequired }.keys)
    }

    suspend fun listing(hostId: String, path: String, forceRefresh: Boolean = false): Listing {
        val key = DirectoryListingKey(hostId, path)
        return listings.getOrLoad(key, forceRefresh) {
            val p = paired(hostId)
            JSONObject(api.request(p.url, p.credential, "/api/v2/fs/list", query = "path" to path)).listing()
        }
    }

    fun prefetchListings(hostId: String, paths: List<String>) {
        paths.distinct().take(12).forEach { path -> scope.launch { runCatching { listing(hostId, path) } } }
    }

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
        mutable.update { it.copy(details = it.details - id) }
        connections.focus(hostId, id)
        withTimeout(15_000) { state.first { it.details[id]?.subscriptionId != null } }
    }

    private suspend fun loadHistory(hostId: String, id: String, subscriptionId: String) {
        val p = paired(hostId)
        val page = JSONObject(api.request(p.url, p.credential, "/api/v2/sessions/$id/history", query = "limit" to "100"))
        val source = page.optJSONObject("source")?.getString("id")
        val items = page.getJSONArray("items").objects().map { it.item() }
        mutable.update { app ->
            val current = app.details[id] ?: return@update app
            if (current.subscriptionId != subscriptionId) return@update app
            app.copy(details = app.details + (id to current.copy(
                timeline = if (current.session.runtimeAttached) current.timeline else items,
                historyItems = items,
                liveItems = if (current.session.runtimeAttached) current.liveItems else emptyList(),
                historySourceId = source,
                nextHistoryCursor = page.optString("nextCursor").takeIf { it.isNotBlank() },
            )))
        }
    }

    suspend fun loadSavedHistory(hostId: String, id: String) {
        val subscriptionId = state.value.details[id]?.subscriptionId ?: return
        loadHistory(hostId, id, subscriptionId)
    }

    suspend fun loadEarlierHistory(hostId: String, id: String) {
        val before = state.value.details[id] ?: return
        val cursor = before.nextHistoryCursor ?: return
        val p = paired(hostId)
        val page = try {
            JSONObject(api.request(p.url, p.credential, "/api/v2/sessions/$id/history", query = "cursor" to cursor))
        } catch (failure: IOException) {
            if ((failure as? GatewayHttpException)?.errorCode == "stale_cursor") {
                before.subscriptionId?.let { loadHistory(hostId, id, it) }
                return
            }
            throw failure
        }
        val source = page.optJSONObject("source")?.getString("id")
        val items = page.getJSONArray("items").objects().map { it.item() }
        mutable.update { app ->
            val current = app.details[id] ?: return@update app
            if (current.subscriptionId != before.subscriptionId || current.historySourceId != source) return@update app
            val history = items + current.historyItems
            app.copy(details = app.details + (id to current.copy(
                timeline = if (current.session.runtimeAttached) current.timeline else history,
                historyItems = history,
                nextHistoryCursor = page.optString("nextCursor").takeIf { it.isNotBlank() },
            )))
        }
    }

    suspend fun models(hostId: String, id: String): ModelCatalog {
        val p = paired(hostId)
        val raw = JSONObject(api.request(p.url, p.credential, "/api/v2/sessions/$id/models"))
        return ModelCatalog(raw.getJSONArray("models").objects().map { it.modelInfo() }, raw.getJSONArray("thinkingLevels").strings())
    }

    suspend fun selectModel(hostId: String, id: String, model: ModelInfo) {
        val generation = requireNotNull(state.value.details[id]?.session?.runtimeGeneration) { "Runtime required" }
        sendCommand(hostId, id, "select_model", JSONObject().put("expectedGeneration", generation).put("provider", model.provider).put("modelId", model.id))
    }

    suspend fun setThinkingLevel(hostId: String, id: String, level: String) {
        val generation = requireNotNull(state.value.details[id]?.session?.runtimeGeneration) { "Runtime required" }
        sendCommand(hostId, id, "set_thinking_level", JSONObject().put("expectedGeneration", generation).put("level", level))
    }

    suspend fun command(hostId: String, id: String, command: String, body: JSONObject = JSONObject()) {
        val session = state.value.details[id]?.session ?: state.value.hosts[hostId]?.sessions?.firstOrNull { it.id == id }
        val generation = session?.runtimeGeneration
        val fields = when (command) {
            "prompt" -> {
                val delivery = if (session?.runtimeExecution == "active") "steer" else "start"
                JSONObject().put("delivery", delivery).put("message", body.getString("message"))
                    .also { if (delivery == "steer") it.put("expectedGeneration", requireNotNull(generation) { "Runtime required" }) }
            }
            "start" -> JSONObject()
            "interrupt", "stop" -> JSONObject().put("expectedGeneration", requireNotNull(generation) { "Runtime required" })
            "respond" -> JSONObject().put("expectedGeneration", requireNotNull(generation) { "Runtime required" })
                .put("inputRequestId", body.getString("id"))
                .also {
                    if (body.has("value")) it.put("value", body.getString("value"))
                    if (body.has("confirmed")) it.put("confirmed", body.getBoolean("confirmed"))
                    if (body.has("cancelled")) it.put("cancelled", body.getBoolean("cancelled"))
                }
            else -> throw IllegalArgumentException("Unknown command")
        }
        sendCommand(hostId, id, when (command) {
            "stop" -> "stop_runtime"
            "start" -> "start_runtime"
            else -> command
        }, fields)
    }

    private suspend fun sendCommand(hostId: String, id: String, type: String, fields: JSONObject) {
        val p = paired(hostId)
        val key = "$hostId:$id:$type:${fields}"
        val commandId = pendingCommandIds.computeIfAbsent(key) { UUID.randomUUID().toString() }
        val body = JSONObject(fields.toString()).put("type", type).put("commandId", commandId)
        val response = try {
            JSONObject(api.request(p.url, p.credential, "/api/v2/sessions/$id/commands", "POST", body))
        } catch (error: IOException) {
            if (error is GatewayHttpException) {
                pendingCommandIds.remove(key, commandId)
                throw error
            }
            val known = runCatching { JSONObject(api.request(p.url, p.credential, "/api/v2/sessions/$id/operations/$commandId")) }.getOrNull()
            if (known == null) throw IOException("Command outcome unconfirmed. Retry the same action to query command $commandId.", error)
            pendingCommandIds.remove(key, commandId)
            checkReceipt(known)
            return
        }
        require(response.optBoolean("receiptStored", true)) { "Stop sent, but its receipt could not be saved; verify the runtime state" }
        pendingCommandIds.remove(key, commandId)
        checkReceipt(response.getJSONObject("operation"))
    }

    private fun checkReceipt(receipt: JSONObject) {
        val status = receipt.optString("status")
        if (status == "outcome_unknown") throw IOException("Command ${receipt.optString("commandId")} has an unconfirmed outcome; inspect the session before sending another command")
        if (status == "failed" || status == "cancelled") {
            val error = receipt.optJSONObject("error")
            throw IOException(error?.optString("message")?.takeIf { it.isNotBlank() } ?: "Command $status")
        }
    }
}
