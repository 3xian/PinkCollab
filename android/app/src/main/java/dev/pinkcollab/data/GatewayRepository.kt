package dev.pinkcollab.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal class GatewayRepository(
    private val scope: CoroutineScope,
    private val credentials: PairedHostStore,
    context: Context,
    private val api: GatewayTransport = GatewayApi(),
    outboxStorage: CommandOutboxStorage? = null,
) {
    private val mutable = MutableStateFlow(AppState(loadingCredentials = true))
    val state = mutable.asStateFlow()
    private val errorChannel = Channel<String>(Channel.BUFFERED)
    val errors = errorChannel.receiveAsFlow()
    private val connections = HostConnectionSupervisor(scope, api, ::connectionState, ::event)
    private val directories = DirectoryGateway(scope, api, ::paired)
    private val attachments = AttachmentUploader(api, ::paired)
    private val initialSyncTimeouts = ConcurrentHashMap<String, Job>()
    private val sessions = SessionGateway(mutable, api, ::paired, connections::focus)
    private val hostCommandGate = HostCommandGate()
    private val commandDispatcher = CommandDispatcher(state, ::paired, api,
        outboxStorage ?: SqliteCommandOutboxStorage(context), hostCommandGate)
    private val pairedHosts = PairedHostRegistry(credentials, mutable, ::connect, ::disconnect)

    init {
        scope.launch {
            try {
                pairedHosts.initialize()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                mutable.update { it.copy(loadingCredentials = false) }
                reportError("Pairing data could not be loaded; pair again: ${failure.message}")
                return@launch
            }
            try {
                commandDispatcher.recoverPendingCommands()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                reportError("Pending commands could not be recovered: ${failure.message}")
            }
        }
    }

    fun reportError(message: String) { errorChannel.trySend(message) }
    private fun paired(id: String) = state.value.hosts[id]?.paired ?: throw IllegalStateException("Host removed")

    suspend fun uploadFile(context: Context, hostId: String, sessionId: String, fileId: String, name: String, uri: Uri) =
        attachments.upload(context, hostId, sessionId, fileId, name, uri)

    suspend fun pair(url: String, token: String) {
        val base = api.validateURL(url)
        val response = JSONObject(api.request(base, null, "/api/v2/pair", "POST", JSONObject().put("token", token.trim()).put("name", "PinkCollab Android")))
        require(response.getInt("protocolVersion") == 2) { "Upgrade PinkCollab to connect to this Gateway" }
        val paired = PairedHost(response.getJSONObject("host").host(), base, response.getString("credential"), response.getString("clientId"))
        pairedHosts.pair(paired)
    }

    suspend fun forget(id: String) {
        hostCommandGate.withHostRemoval(id) {
            pairedHosts.forget(id)
            try {
                commandDispatcher.removeHost(id)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                // The pairing is already removed; still let the UI release its host-owned state.
                reportError("Host removed, but pending command cleanup failed: ${failure.message}")
            }
        }
    }

    private fun disconnect(id: String) {
        connections.forget(id)
        initialSyncTimeouts.remove(id)?.cancel()
        directories.removeHost(id)
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
            val reduced = reduceV2(app, hostId, frame, System.currentTimeMillis())
            reduced.state to reduced
        }
        if (frame.optString("type") == "snapshot" && frame.optString("resource") == "host/sessions") {
            initialSyncTimeouts.remove(hostId)?.cancel()
        }
        reduction.effects.forEach { effect ->
            when (effect) {
                is GatewayEffect.ResyncResource -> {
                    if (effect.resource == "host/sessions") state.value.hosts[effect.hostId]?.paired?.let(::connect)
                    else if (effect.resource.startsWith("session/")) {
                        val sessionId = effect.resource.removePrefix("session/")
                        if (connections.isDesired(effect.hostId, sessionId)) connections.subscribe(effect.hostId, sessionId)
                    }
                }
                is GatewayEffect.LoadHistory -> {
                    val subscriptionId = state.value.details[effect.session]?.subscriptionId
                    if (subscriptionId != null) scope.launch {
                        runCatching { sessions.loadHistory(effect.session.hostId, effect.session.sessionId, subscriptionId) }
                            .onFailure { reportError(it.message ?: "History unavailable") }
                    }
                }
            }
        }
    }

    suspend fun refreshHost(id: String) {
        val before = state.value.hosts[id]?.subscriptionId
        connect(paired(id))
        awaitSnapshot(state, "Timed out waiting for the host snapshot") {
            it.hosts[id]?.subscriptionId?.let { subscription -> subscription != before } == true
        }
    }

    fun requestReconnect(id: String) {
        if (state.value.hosts[id] == null) return
        mutable.update { app ->
            val host = app.hosts[id] ?: return@update app
            app.copy(hosts = app.hosts + (id to host.copy(initialSync = InitialSyncState.Pending)))
        }
        state.value.hosts[id]?.paired?.let(::connect)
    }

    fun reconnectUnavailableHosts() {
        state.value.hosts.values.filter { it.connection == ConnectionState.Reconnecting || it.connection is ConnectionState.Offline }.forEach { connect(it.paired) }
    }

    fun networkUnavailable() {
        connections.networkUnavailable(state.value.hosts.filterValues { it.connection != ConnectionState.AuthenticationRequired && it.connection != ConnectionState.UpgradeRequired }.keys)
    }

    suspend fun listing(hostId: String, path: String, forceRefresh: Boolean = false) =
        directories.listing(hostId, path, forceRefresh)

    fun prefetchListings(hostId: String, paths: List<String>) = directories.prefetch(hostId, paths)

    suspend fun create(hostId: String, cwd: String) = sessions.create(hostId, cwd)
    suspend fun detail(hostId: String, id: String) = sessions.detail(hostId, id)
    suspend fun loadSavedHistory(hostId: String, id: String) = sessions.loadSavedHistory(hostId, id)
    suspend fun loadEarlierHistory(hostId: String, id: String) = sessions.loadEarlierHistory(hostId, id)
    suspend fun models(hostId: String, id: String) = sessions.models(hostId, id)
    suspend fun selectModel(hostId: String, id: String, model: ModelInfo) = commandDispatcher.selectModel(hostId, id, model)
    suspend fun setThinkingLevel(hostId: String, id: String, level: String) = commandDispatcher.setThinkingLevel(hostId, id, level)
    suspend fun prompt(hostId: String, id: String, message: String, fileIds: List<String>, intentId: String) =
        commandDispatcher.prompt(hostId, id, message, fileIds, intentId)
    suspend fun respond(hostId: String, id: String, response: AttentionResponse) = commandDispatcher.respond(hostId, id, response)
    suspend fun command(hostId: String, id: String, command: String) = commandDispatcher.command(hostId, id, command)
}

internal suspend fun <T> awaitSnapshot(state: kotlinx.coroutines.flow.StateFlow<T>, message: String, ready: (T) -> Boolean) {
    withTimeoutOrNull(15_000) { state.first(ready) } ?: throw IOException(message)
}

internal fun <S, R> updateAtomically(state: MutableStateFlow<S>, reduce: (S) -> Pair<S, R>): R {
    while (true) {
        val current = state.value
        val (updated, result) = reduce(current)
        if (state.compareAndSet(current, updated)) return result
    }
}
