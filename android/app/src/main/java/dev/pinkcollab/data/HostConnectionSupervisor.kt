package dev.pinkcollab.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal class HostConnectionSupervisor(
    private val scope: CoroutineScope,
    private val api: GatewayTransport,
    private val onState: (String, ConnectionState, ConnectionProgress?) -> Unit,
    private val onFrame: (String, JSONObject) -> Unit,
    private val onSubscriptionEnded: (String, String) -> Unit,
    private val cachedHost: (String) -> HostState? = { null },
    private val cachedSession: (String, String) -> SessionDetail? = { _, _ -> null },
) {
    private val lock = Any()
    private val connections = mutableMapOf<String, HostConnection>()
    private val desiredSessions = mutableMapOf<String, MutableSet<String>>()

    fun connect(paired: PairedHost) {
        synchronized(lock) {
            invalidateSubscriptions(paired.host.id)
            val connection = HostConnection(paired)
            connections.put(paired.host.id, connection)?.stop()
            connection.start()
        }
    }

    fun forget(hostId: String) {
        synchronized(lock) {
            invalidateSubscriptions(hostId)
            connections.remove(hostId)?.stop()
            desiredSessions.remove(hostId)
        }
    }

    fun subscribe(hostId: String, sessionId: String) {
        synchronized(lock) {
            desiredSessions.getOrPut(hostId) { mutableSetOf() }.add(sessionId)
            onSubscriptionEnded(hostId, sessionId)
            connections[hostId]?.subscribe(sessionId)
        }
    }

    fun isDesired(hostId: String, sessionId: String): Boolean =
        synchronized(lock) { desiredSessions[hostId]?.contains(sessionId) == true }

    fun focus(hostId: String, sessionId: String) {
        synchronized(lock) {
            desiredSessions.forEach { (otherHost, sessions) ->
                sessions.toList().filter { otherHost != hostId || it != sessionId }.forEach { old ->
                    unsubscribe(otherHost, old)
                }
            }
            subscribe(hostId, sessionId)
        }
    }

    fun unsubscribe(hostId: String, sessionId: String) {
        synchronized(lock) {
            desiredSessions[hostId]?.remove(sessionId)
            onSubscriptionEnded(hostId, sessionId)
            connections[hostId]?.unsubscribe(sessionId)
        }
    }

    fun resync(hostId: String, sessionId: String) {
        synchronized(lock) {
            onSubscriptionEnded(hostId, sessionId)
            connections[hostId]?.subscribe(sessionId, fresh = true)
        }
    }

    fun networkUnavailable(hostIds: Collection<String>) {
        synchronized(lock) {
            hostIds.forEach {
                invalidateSubscriptions(it)
                connections[it]?.markOffline()
            }
        }
    }

    private fun invalidateSubscriptions(hostId: String) {
        desiredSessions[hostId]?.forEach { onSubscriptionEnded(hostId, it) }
    }

    private inner class HostConnection(private val paired: PairedHost) {
        private val connectionGeneration = AtomicLong()
        private var job: Job? = null
        private var socket: WebSocket? = null

        fun subscribe(sessionId: String, fresh: Boolean = false) {
            val cached = cachedSession(paired.host.id, sessionId)
            val command = JSONObject().put("type", "subscribe").put("sessionId", sessionId)
                .put("historyLimit", InitialHistoryPageSize).put("hasCachedHistory", cached?.savedHistory is SavedHistory.Ready)
            if (!fresh && cached?.serverEpoch != null) command.put("epoch", cached.serverEpoch).put("sequence", cached.wireSequence)
            socket?.send(command.toString())
        }

        fun unsubscribe(sessionId: String) {
            socket?.send(JSONObject().put("type", "unsubscribe").put("sessionId", sessionId).toString())
        }

        fun start() {
            val currentGeneration = connectionGeneration.incrementAndGet()
            job?.cancel()
            job = scope.launch {
                var lastFailure: String? = null
                for (attempt in 1..MaxConnectionAttempts) {
                    if (!isActive || !isCurrent(currentGeneration)) return@launch
                    // Socket opening and host synchronization are separate connection stages.
                    val progress = ConnectionProgress(attempt, lastFailure)
                    emitState(currentGeneration, ConnectionState.Connecting, progress)
                    val disconnect = awaitSocket(currentGeneration, progress)
                    if (!isCurrent(currentGeneration)) return@launch
                    if (disconnect.authenticationRequired) {
                        emitState(currentGeneration, ConnectionState.AuthenticationRequired)
                        return@launch
                    }
                    if (disconnect.upgradeRequired) {
                        emitState(currentGeneration, ConnectionState.UpgradeRequired)
                        return@launch
                    }
                    lastFailure = disconnect.failure.reason
                    emitState(currentGeneration, disconnect.failure)
                    if (attempt == MaxConnectionAttempts) return@launch
                    delay(ReconnectIntervalMillis)
                }
            }
        }

        fun stop() {
            connectionGeneration.incrementAndGet()
            socket?.cancel()
            socket = null
            job?.cancel()
            job = null
        }

        fun markOffline() {
            stop()
            synchronized(lock) {
                if (connections[paired.host.id] === this) {
                    onState(paired.host.id, ConnectionState.Offline("Network unavailable", ConnectionFailure.NetworkUnavailable), null)
                }
            }
        }

        private fun isCurrent(generation: Long): Boolean =
            synchronized(lock) {
                connectionGeneration.get() == generation && connections[paired.host.id] === this
            }

        private fun emitState(generation: Long, state: ConnectionState, progress: ConnectionProgress? = null) {
            synchronized(lock) {
                if (connectionGeneration.get() == generation && connections[paired.host.id] === this) {
                    if (state is ConnectionState.Offline || state == ConnectionState.AuthenticationRequired ||
                        state == ConnectionState.UpgradeRequired) invalidateSubscriptions(paired.host.id)
                    onState(paired.host.id, state, progress)
                }
            }
        }

        private fun handleFrame(generation: Long, frame: JSONObject) {
            synchronized(lock) {
                if (connectionGeneration.get() == generation && connections[paired.host.id] === this) {
                    val sessionId = frame.optString("sessionId")
                    if (frame.optString("type") in setOf("session_snapshot", "session_resume", "session_sync", "timeline", "message_patch", "operation") &&
                        desiredSessions[paired.host.id]?.contains(sessionId) != true) return
                    onFrame(paired.host.id, frame)
                }
            }
        }

        private suspend fun awaitSocket(generation: Long, progress: ConnectionProgress): Disconnect =
            suspendCancellableCoroutine { continuation ->
                val known = cachedHost(paired.host.id)
                val endpoint = (paired.url + "/api/v4/events").toHttpUrl().newBuilder().apply {
                    synchronized(lock) { desiredSessions[paired.host.id]?.firstOrNull() }?.let { addQueryParameter("focus", it) }
                    known?.catalogVersion?.let { addQueryParameter("catalog", it) }
                    if (known?.serverEpoch != null) addQueryParameter("epoch", known.serverEpoch).addQueryParameter("sequence", known.wireSequence.toString())
                }.build()
                val socket = api.client.newWebSocket(
                    Request.Builder()
                        .url(endpoint)
                        .header("Authorization", "Bearer ${paired.credential}")
                        .header("Sec-WebSocket-Protocol", GzipSocketProtocol)
                        .build(),
                    object : WebSocketListener() {
                        private var gzipNegotiated = false
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            if (!isCurrent(generation)) {
                                webSocket.cancel()
                                return
                            }
                            gzipNegotiated = response.header("Sec-WebSocket-Protocol") == GzipSocketProtocol
                            emitState(generation, ConnectionState.Synchronizing, progress)
                            synchronized(lock) {
                                socket = webSocket
                                desiredSessions[paired.host.id]?.forEach(::subscribe)
                            }
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) = acceptFrame(webSocket, text)

                        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                            if (!isCurrent(generation)) return
                            runCatching { decodeSocketFrame(bytes, gzipNegotiated) }
                                .onSuccess { acceptFrame(webSocket, it) }
                                .onFailure { webSocket.close(1002, "Invalid protocol frame") }
                        }

                        private fun acceptFrame(webSocket: WebSocket, text: String) {
                            if (!isCurrent(generation)) return
                            runCatching {
                                val frame = JSONObject(text)
                                handleFrame(generation, frame)
                            }.onFailure { webSocket.close(1002, "Invalid protocol frame") }
                        }

                        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                            synchronized(lock) { if (socket === webSocket) socket = null }
                            if (continuation.isActive) {
                                continuation.resume(
                                    Disconnect(
                                        authenticationRequired = response?.code == 401,
                                        upgradeRequired = response?.code == 426,
                                        failure = connectionFailure(error, response?.code),
                                    ),
                                )
                            }
                        }

                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            synchronized(lock) { if (socket === webSocket) socket = null }
                            if (continuation.isActive) {
                                continuation.resume(
                                    Disconnect(
                                        failure = if (code == 1002)
                                            ConnectionState.Offline("Invalid connection data", ConnectionFailure.InvalidData)
                                        else ConnectionState.Offline("Host closed the connection", ConnectionFailure.HostClosed),
                                    ),
                                )
                            }
                        }
                    },
                )
                continuation.invokeOnCancellation { socket.cancel() }
            }
    }

    private data class Disconnect(
        val authenticationRequired: Boolean = false,
        val upgradeRequired: Boolean = false,
        val failure: ConnectionState.Offline = ConnectionState.Offline("Connection interrupted"),
    )
}

internal fun connectionFailure(error: Throwable, httpCode: Int? = null): ConnectionState.Offline = when {
    httpCode != null -> ConnectionState.Offline("Host rejected the connection (HTTP $httpCode)", ConnectionFailure.HostRejected)
    error is UnknownHostException -> ConnectionState.Offline("Cannot resolve host address", ConnectionFailure.HostNotFound)
    error is SocketTimeoutException -> ConnectionState.Offline("Connection timed out", ConnectionFailure.TimedOut)
    error is ConnectException -> ConnectionState.Offline("Cannot reach host address or port", ConnectionFailure.Unreachable)
    error is SSLException -> ConnectionState.Offline("Secure connection failed", ConnectionFailure.SecureConnectionFailed)
    else -> ConnectionState.Offline("Connection interrupted")
}

private const val MaxConnectionAttempts = 3
private const val ReconnectIntervalMillis = 1_000L
