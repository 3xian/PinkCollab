package dev.pinkcollab.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val pendingCommands = PendingCommandStore()

    init {
        runCatching { credentials.read() }.onSuccess { saved ->
            mutable.update { it.copy(hosts = saved.associate { paired -> paired.host.id to HostState(paired) }) }
            saved.forEach(::connect)
        }.onFailure { error("Pairing data could not be decrypted; pair again: ${it.message}") }
    }

    fun error(message: String?) { mutable.update { it.copy(error = message) } }
    private fun paired(id: String) = state.value.hosts[id]?.paired ?: throw IllegalStateException("Host removed")

    suspend fun uploadFile(context: Context, hostId: String, sessionId: String, fileId: String, name: String, uri: Uri) {
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 10 * 1024 * 1024) throw IOException("File exceeds the 10 MiB limit")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: throw IOException("Cannot open selected file")
        }
        require(bytes.isNotEmpty()) { "Empty files cannot be sent" }
        val host = paired(hostId)
        val response = try {
            api.upload(host.url, host.credential, "/api/v2/sessions/$sessionId/files/$fileId", name, bytes)
        } catch (error: GatewayHttpException) {
            if (error.statusCode == 404 && error.errorCode.isNullOrBlank()) throw IOException("Update the Gateway to send files", error)
            throw error
        }
        val result = JSONObject(response)
        require(result.getString("fileId") == fileId) { "Gateway returned a different file ID" }
    }

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
        val reduction = updateAtomically(mutable) { app ->
            val reduced = reduceV2(app, hostId, frame)
            reduced.state to reduced
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
        state.value.hosts.values.filter { it.connection == ConnectionState.Reconnecting || it.connection is ConnectionState.Offline }.forEach { connect(it.paired) }
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
                nextHistoryCursor = historyCursor(page),
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
                nextHistoryCursor = historyCursor(page),
            )))
        }
    }

    suspend fun models(hostId: String, id: String): ModelCatalog {
        val p = paired(hostId)
        val raw = JSONObject(api.request(p.url, p.credential, "/api/v2/sessions/$id/models"))
        return ModelCatalog(raw.getJSONArray("models").objects().map { it.modelInfo() }, raw.getJSONArray("thinkingLevels").strings())
    }

    suspend fun selectModel(hostId: String, id: String, model: ModelInfo) {
        sendCommand(hostId, id, "select_model:${model.provider}:${model.id}", "select_model") {
            val generation = requireNotNull(state.value.details[id]?.session?.runtimeGeneration) { "Runtime required" }
            JSONObject().put("expectedGeneration", generation).put("provider", model.provider).put("modelId", model.id)
        }
    }

    suspend fun setThinkingLevel(hostId: String, id: String, level: String) {
        sendCommand(hostId, id, "set_thinking_level:$level", "set_thinking_level") {
            val generation = requireNotNull(state.value.details[id]?.session?.runtimeGeneration) { "Runtime required" }
            JSONObject().put("expectedGeneration", generation).put("level", level)
        }
    }

    suspend fun command(hostId: String, id: String, command: String, body: JSONObject = JSONObject()) {
        val requested = body.toString()
        sendCommand(hostId, id, "$command:$requested", when (command) {
            "stop" -> "stop_runtime"
            "start" -> "start_runtime"
            else -> command
        }) {
            val input = JSONObject(requested)
            val session = state.value.details[id]?.session ?: state.value.hosts[hostId]?.sessions?.firstOrNull { it.id == id }
            val generation = session?.runtimeGeneration
            when (command) {
                "prompt" -> {
                    val delivery = if (session?.runtimeExecution == "active") "steer" else "start"
                    JSONObject().put("delivery", delivery).put("message", input.getString("message"))
                        .also { if (input.has("fileIds")) it.put("fileIds", input.getJSONArray("fileIds")) }
                        .also { if (delivery == "steer") it.put("expectedGeneration", requireNotNull(generation) { "Runtime required" }) }
                }
                "start" -> JSONObject()
                "interrupt", "stop" -> JSONObject().put("expectedGeneration", requireNotNull(generation) { "Runtime required" })
                "respond" -> JSONObject().put("expectedGeneration", requireNotNull(generation) { "Runtime required" })
                    .put("inputRequestId", input.getString("id"))
                    .also {
                        if (input.has("value")) it.put("value", input.getString("value"))
                        if (input.has("confirmed")) it.put("confirmed", input.getBoolean("confirmed"))
                        if (input.has("cancelled")) it.put("cancelled", input.getBoolean("cancelled"))
                    }
                else -> throw IllegalArgumentException("Unknown command")
            }
        }
    }

    private suspend fun sendCommand(hostId: String, id: String, action: String, type: String, fields: () -> JSONObject) {
        val p = paired(hostId)
        submitPendingCommand(
            pendingCommands, "$hostId:$id:$action", type, fields,
            post = { body -> JSONObject(api.request(p.url, p.credential, "/api/v2/sessions/$id/commands", "POST", body)) },
            lookup = { commandId -> JSONObject(api.request(p.url, p.credential, "/api/v2/sessions/$id/operations/$commandId")) },
        )
    }
}

internal fun historyCursor(page: JSONObject): String? = (page.opt("nextCursor") as? String)?.takeIf { it.isNotBlank() }

internal data class PendingCommand(val id: String, val payload: String) {
    @Volatile var attempted = false
    @Volatile var lookupOnly = false
}

internal class PendingCommandStore {
    private val values = ConcurrentHashMap<String, PendingCommand>()
    fun getOrCreate(key: String, create: () -> PendingCommand): PendingCommand = values.computeIfAbsent(key) { create() }
    fun remove(key: String, pending: PendingCommand) { values.remove(key, pending) }
}

internal suspend fun submitPendingCommand(
    commands: PendingCommandStore,
    key: String,
    type: String,
    fields: () -> JSONObject,
    post: suspend (JSONObject) -> JSONObject,
    lookup: suspend (String) -> JSONObject,
) {
    val pending = commands.getOrCreate(key) {
        val commandId = UUID.randomUUID().toString()
        PendingCommand(commandId, JSONObject(fields().toString()).put("type", type).put("commandId", commandId).toString())
    }
    val commandId = pending.id
    if (pending.attempted) {
        val known = runCatching { lookup(commandId) }.getOrNull()
        if (known != null) {
            if (known.optString("status") != "outcome_unknown") commands.remove(key, pending)
            checkReceipt(known)
            return
        }
        if (pending.lookupOnly) throw IOException("Command outcome unconfirmed; inspect the session before retrying command $commandId")
    }
    pending.attempted = true
    val response = try {
        post(JSONObject(pending.payload))
    } catch (error: IOException) {
        if (error is GatewayHttpException && error.statusCode in 400..499 && error.statusCode != 408 && error.statusCode != 429) {
            commands.remove(key, pending)
            throw error
        }
        val known = runCatching { lookup(commandId) }.getOrNull()
        if (known == null) throw IOException("Command outcome unconfirmed. Retry the same action to query command $commandId.", error)
        if (known.optString("status") != "outcome_unknown") commands.remove(key, pending)
        checkReceipt(known)
        return
    }
    if (!response.optBoolean("receiptStored", true)) {
        pending.lookupOnly = true
        throw IOException("Stop sent, but its receipt could not be saved; verify the runtime state")
    }
    val receipt = response.getJSONObject("operation")
    if (receipt.optString("status") != "outcome_unknown") commands.remove(key, pending)
    checkReceipt(receipt)
}

private fun checkReceipt(receipt: JSONObject) {
    val status = receipt.optString("status")
    if (status == "outcome_unknown") throw IOException("Command ${receipt.optString("commandId")} has an unconfirmed outcome; inspect the session before sending another command")
    if (status == "failed" || status == "cancelled") {
        val error = receipt.optJSONObject("error")
        throw IOException(error?.optString("message")?.takeIf { it.isNotBlank() } ?: "Command $status")
    }
}

internal fun <S, R> updateAtomically(state: MutableStateFlow<S>, reduce: (S) -> Pair<S, R>): R {
    while (true) {
        val current = state.value
        val (updated, result) = reduce(current)
        if (state.compareAndSet(current, updated)) return result
    }
}
