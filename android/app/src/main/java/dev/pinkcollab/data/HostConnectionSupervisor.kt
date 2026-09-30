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
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.random.Random
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

        fun subscribe(sessionId: String) {
            socket?.send(JSONObject().put("type", "subscribe").put("sessionId", sessionId)
                .put("historyLimit", InitialHistoryPageSize).toString())
        }

        fun unsubscribe(sessionId: String) {
            socket?.send(JSONObject().put("type", "unsubscribe").put("sessionId", sessionId).toString())
        }

        fun start() {
            val currentGeneration = connectionGeneration.incrementAndGet()
            job?.cancel()
            job = scope.launch {
                var failedAttempts = 0
                var lastFailure: String? = null
                while (isActive && isCurrent(currentGeneration)) {
                    // Socket opening and host synchronization are separate connection stages.
                    val progress = ConnectionProgress(failedAttempts + 1, lastFailure)
                    emitState(currentGeneration,
                        if (failedAttempts == 0) ConnectionState.Connecting
                        else retryConnectionState(failedAttempts, lastFailure), progress)
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
                    failedAttempts = if (disconnect.hadSnapshot) 1 else failedAttempts + 1
                    lastFailure = disconnect.reason
                    val retryDelay = retryDelayMillis(failedAttempts)
                    emitState(currentGeneration, retryConnectionState(failedAttempts, lastFailure),
                        ConnectionProgress(failedAttempts + 1, lastFailure))
                    delay(retryDelay)
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
                    onState(paired.host.id, ConnectionState.Offline("Network unavailable"), null)
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
                    if (state == ConnectionState.Reconnecting || state is ConnectionState.Offline || state == ConnectionState.AuthenticationRequired ||
                        state == ConnectionState.UpgradeRequired) invalidateSubscriptions(paired.host.id)
                    onState(paired.host.id, state, progress)
                }
            }
        }

        private fun handleFrame(generation: Long, frame: JSONObject) {
            synchronized(lock) {
                if (connectionGeneration.get() == generation && connections[paired.host.id] === this) {
                    val sessionId = frame.optString("sessionId")
                    if (frame.optString("type") in setOf("session_snapshot", "timeline", "operation") &&
                        desiredSessions[paired.host.id]?.contains(sessionId) != true) return
                    onFrame(paired.host.id, frame)
                }
            }
        }

        private suspend fun awaitSocket(generation: Long, progress: ConnectionProgress): Disconnect =
            suspendCancellableCoroutine { continuation ->
                val hadSnapshot = AtomicBoolean()
                val socket = api.client.newWebSocket(
                    Request.Builder()
                        .url(paired.url + "/api/v3/events")
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
                                if (frame.getString("type") == "host_snapshot") hadSnapshot.set(true)
                            }.onFailure { webSocket.close(1002, "Invalid protocol frame") }
                        }

                        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                            synchronized(lock) { if (socket === webSocket) socket = null }
                            if (continuation.isActive) {
                                continuation.resume(
                                    Disconnect(
                                        authenticationRequired = response?.code == 401,
                                        upgradeRequired = response?.code == 426,
                                        hadSnapshot = hadSnapshot.get(),
                                        reason = connectionFailureReason(error, response?.code),
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
                                        hadSnapshot = hadSnapshot.get(),
                                        reason = if (code == 1002) "Invalid connection data" else "Host closed the connection",
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
        val hadSnapshot: Boolean = false,
        val reason: String = "Connection interrupted",
    )
}

internal fun connectionFailureReason(error: Throwable, httpCode: Int? = null): String = when {
    httpCode != null -> "Host rejected the connection (HTTP $httpCode)"
    error is UnknownHostException -> "Cannot resolve host address"
    error is SocketTimeoutException -> "Connection timed out"
    error is ConnectException -> "Cannot reach host address or port"
    error is SSLException -> "Secure connection failed"
    else -> "Connection interrupted"
}

// Keep brief interruptions responsive, but treat a persistently unreachable host as offline.
internal fun retryConnectionState(failedAttempts: Int, reason: String?): ConnectionState =
    if (failedAttempts >= 7) ConnectionState.Offline(reason) else ConnectionState.Reconnecting

internal fun retryDelayMillis(attempt: Int, jitter: Double = Random.nextDouble(0.8, 1.2)): Long {
    val base = when (attempt) {
        1 -> 0L
        2 -> 1_000L
        3 -> 2_000L
        4 -> 4_000L
        5 -> 8_000L
        6 -> 15_000L
        7 -> 60_000L
        8 -> 120_000L
        else -> 300_000L
    }
    return (base * jitter).toLong().coerceAtMost(300_000L)
}
