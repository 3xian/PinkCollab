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

internal class HostConnectionSupervisor(
    private val scope: CoroutineScope,
    private val api: GatewayApi,
    private val onState: (String, ConnectionState) -> Unit,
    private val onFrame: (String, JSONObject) -> Unit,
) {
    private val lock = Any()
    private val connections = mutableMapOf<String, HostConnection>()
    private val desiredSessions = mutableMapOf<String, MutableSet<String>>()

    fun connect(paired: PairedHost) {
        synchronized(lock) {
            val connection = HostConnection(paired)
            connections.put(paired.host.id, connection)?.stop()
            connection.start()
        }
    }

    fun forget(hostId: String) {
        synchronized(lock) { connections.remove(hostId)?.stop(); desiredSessions.remove(hostId) }
    }

    fun subscribe(hostId: String, sessionId: String) {
        synchronized(lock) {
            desiredSessions.getOrPut(hostId) { mutableSetOf() }.add(sessionId)
            connections[hostId]?.subscribe(sessionId)
        }
    }

    fun isDesired(hostId: String, sessionId: String): Boolean =
        synchronized(lock) { desiredSessions[hostId]?.contains(sessionId) == true }

    fun focus(hostId: String, sessionId: String) {
        synchronized(lock) {
            desiredSessions.forEach { (otherHost, sessions) ->
                sessions.toList().filter { otherHost != hostId || it != sessionId }.forEach { old ->
                    sessions.remove(old)
                    connections[otherHost]?.unsubscribe(old)
                }
            }
            desiredSessions.getOrPut(hostId) { mutableSetOf() }.add(sessionId)
            connections[hostId]?.subscribe(sessionId)
        }
    }

    fun unsubscribe(hostId: String, sessionId: String) {
        synchronized(lock) {
            desiredSessions[hostId]?.remove(sessionId)
            connections[hostId]?.unsubscribe(sessionId)
        }
    }

    fun networkUnavailable(hostIds: Collection<String>) {
        synchronized(lock) { hostIds.forEach { connections[it]?.markOffline() } }
    }

    private inner class HostConnection(private val paired: PairedHost) {
        private val generation = AtomicLong()
        private var job: Job? = null
        private var socket: WebSocket? = null

        fun subscribe(sessionId: String) {
            socket?.send(JSONObject().put("type", "subscribe").put("resource", "session/$sessionId").toString())
        }

        fun unsubscribe(sessionId: String) {
            socket?.send(JSONObject().put("type", "unsubscribe").put("resource", "session/$sessionId").toString())
        }

        fun start() {
            val currentGeneration = generation.incrementAndGet()
            job?.cancel()
            job = scope.launch {
                var failedAttempts = 0
                while (isActive && isCurrent(currentGeneration)) {
                    // Keep the retry status visible until a fresh snapshot arrives.
                    val initialAttempt = failedAttempts == 0
                    if (initialAttempt) emitState(currentGeneration, ConnectionState.Connecting)
                    val disconnect = awaitSocket(currentGeneration, initialAttempt)
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
                    val retryDelay = retryDelayMillis(failedAttempts)
                    emitState(currentGeneration, ConnectionState.Reconnecting)
                    delay(retryDelay)
                }
            }
        }

        fun stop() {
            generation.incrementAndGet()
            socket?.cancel()
            socket = null
            job?.cancel()
            job = null
        }

        fun markOffline() {
            stop()
            synchronized(lock) {
                if (connections[paired.host.id] === this) {
                    onState(paired.host.id, ConnectionState.Offline("Network unavailable"))
                }
            }
        }

        private fun isCurrent(expectedGeneration: Long): Boolean =
            synchronized(lock) {
                generation.get() == expectedGeneration && connections[paired.host.id] === this
            }

        private fun emitState(expectedGeneration: Long, state: ConnectionState) {
            synchronized(lock) {
                if (generation.get() == expectedGeneration && connections[paired.host.id] === this) {
                    onState(paired.host.id, state)
                }
            }
        }

        private fun handleFrame(expectedGeneration: Long, frame: JSONObject) {
            synchronized(lock) {
                if (generation.get() == expectedGeneration && connections[paired.host.id] === this) {
                    onFrame(paired.host.id, frame)
                }
            }
        }

        private suspend fun awaitSocket(expectedGeneration: Long, initialAttempt: Boolean): Disconnect =
            suspendCancellableCoroutine { continuation ->
                val hadSnapshot = AtomicBoolean()
                val socket = api.client.newWebSocket(
                    Request.Builder()
                        .url(paired.url + "/api/v2/events")
                        .header("Authorization", "Bearer ${paired.credential}")
                        .build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            if (!isCurrent(expectedGeneration)) {
                                webSocket.cancel()
                                return
                            }
                            if (initialAttempt) emitState(expectedGeneration, ConnectionState.Synchronizing)
                            synchronized(lock) {
                                socket = webSocket
                                desiredSessions[paired.host.id]?.forEach(::subscribe)
                            }
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (!isCurrent(expectedGeneration)) return
                            runCatching {
                                val frame = JSONObject(text)
                                handleFrame(expectedGeneration, frame)
                                if (frame.getString("type") == "snapshot") hadSnapshot.set(true)
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
    )
}

internal fun retryDelayMillis(attempt: Int, jitter: Double = Random.nextDouble(0.8, 1.2)): Long {
    val base = when (attempt) {
        1 -> 0L
        2 -> 1_000L
        3 -> 2_000L
        4 -> 4_000L
        5 -> 8_000L
        6 -> 15_000L
        else -> 30_000L
    }
    return (base * jitter).toLong().coerceAtMost(30_000L)
}
