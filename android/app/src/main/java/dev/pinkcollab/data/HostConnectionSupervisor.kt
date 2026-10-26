package dev.pinkcollab.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal class HostConnectionSupervisor(
    private val scope: CoroutineScope,
    private val api: GatewayTransport,
    private val onState: (String, ConnectionState) -> Unit,
    private val onFrame: (String, JSONObject) -> Unit,
    private val onSubscriptionEnded: (String, String) -> Unit,
    private val cachedHost: (String) -> HostState? = { null },
    private val cachedSession: (String, String) -> SessionDetail? = { _, _ -> null },
) {
    private val lock = Any()
    private val connections = mutableMapOf<String, HostConnection>()
    private val desiredSessions = mutableMapOf<String, MutableSet<String>>()
    private var foreground = false
    private var networkAvailable = true

    fun connect(paired: PairedHost, refreshDiscovery: Boolean = false): Deferred<Unit> =
        synchronized(lock) {
            invalidateSubscriptions(paired.host.id)
            val connection = HostConnection(paired)
            connections.put(paired.host.id, connection)?.stop()
            connection.start(refreshDiscovery)
            connection.settled
        }

    fun setForeground(value: Boolean) {
        synchronized(lock) {
            if (foreground == value) return
            foreground = value
            connections.values.forEach {
                if (value) it.grantRecoveryOpportunity() else it.clearRecoveryOpportunity()
            }
        }
    }

    fun setNetworkAvailable(value: Boolean) {
        synchronized(lock) {
            if (networkAvailable == value) return
            networkAvailable = value
            if (value) {
                connections.values.forEach { it.grantRecoveryOpportunity() }
            } else {
                desiredSessions.keys.filterNot(connections::containsKey).forEach(::invalidateSubscriptions)
                connections.values.forEach { it.markOffline() }
            }
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
            if (desiredSessions[hostId]?.contains(sessionId) == true) {
                connections[hostId]?.subscribe(sessionId, fresh = true)
            }
        }
    }

    private fun invalidateSubscriptions(hostId: String) {
        desiredSessions[hostId]?.forEach { onSubscriptionEnded(hostId, it) }
    }

    private inner class HostConnection(private val paired: PairedHost) {
        private var phase: ConnectionState = ConnectionState.Offline()
        private var attempt: Attempt? = null
        private var recoveryEligible = false
        val settled = CompletableDeferred<Unit>()

        private val blocked: Boolean
            get() = phase == ConnectionState.AuthenticationRequired || phase == ConnectionState.UpgradeRequired

        fun subscribe(sessionId: String, fresh: Boolean = false) {
            attempt?.subscribe(sessionId, fresh)
        }

        fun unsubscribe(sessionId: String) {
            attempt?.unsubscribe(sessionId)
        }

        fun start(refreshDiscovery: Boolean = false) {
            attempt?.close()
            if (!networkAvailable) {
                markOffline()
                return
            }
            phase = ConnectionState.Connecting
            val next = Attempt()
            attempt = next
            next.start(refreshDiscovery)
        }

        fun stop() {
            clearRecoveryOpportunity()
            attempt?.close()
            settled.complete(Unit)
        }

        fun clearRecoveryOpportunity() {
            recoveryEligible = false
        }

        fun markOffline() {
            if (blocked) return
            attempt?.close()
            publish(ConnectionState.Offline("Network unavailable", ConnectionFailure.NetworkUnavailable))
            settled.complete(Unit)
        }

        fun grantRecoveryOpportunity() {
            if (!foreground || blocked) return
            // A restoration belongs to an offline host or its already-established socket,
            // not to an attempt that is still connecting or synchronizing.
            recoveryEligible = phase is ConnectionState.Offline || phase is ConnectionState.Online
            recoverIfEligible()
        }

        private fun recoverIfEligible() {
            if (!recoveryEligible || !foreground || !networkAvailable || phase !is ConnectionState.Offline ||
                connections[paired.host.id] !== this) return
            recoveryEligible = false
            start()
        }

        private fun publish(state: ConnectionState) {
            phase = state
            if (state is ConnectionState.Offline || blocked) invalidateSubscriptions(paired.host.id)
            onState(paired.host.id, state)
        }

        private inner class Attempt : WebSocketListener() {
            private var socket: WebSocket? = null
            private var job: Job? = null
            private var syncTimeoutJob: Job? = null
            private var hostSynchronized = false
            private val pendingSessions = mutableSetOf<String>()
            private var gzipNegotiated = false
            private val current: Boolean
                get() = attempt === this && connections[paired.host.id] === this@HostConnection

            fun start(refreshDiscovery: Boolean) {
                job = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val request = synchronized(lock) {
                            if (!isActive || !current) return@launch
                            publish(ConnectionState.Connecting)
                            if (!current) return@launch
                            armSyncTimeout()
                            val known = cachedHost(paired.host.id)
                            val endpoint = (paired.url + "/api/v4/events").toHttpUrl().newBuilder().apply {
                                desiredSessions[paired.host.id]?.firstOrNull()?.let { addQueryParameter("focus", it) }
                                if (refreshDiscovery) addQueryParameter("refresh", "1")
                                known?.catalogVersion?.let { addQueryParameter("catalog", it) }
                                if (known?.serverEpoch != null) addQueryParameter("epoch", known.serverEpoch)
                                    .addQueryParameter("sequence", known.wireSequence.toString())
                            }.build()
                            Request.Builder().url(endpoint)
                                .header("Authorization", "Bearer ${paired.credential}")
                                .header("Sec-WebSocket-Protocol", GzipSocketProtocol)
                                .build()
                        }
                        val created = api.client.newWebSocket(request, this@Attempt)
                        synchronized(lock) {
                            if (current) socket = created else created.cancel()
                        }
                        awaitCancellation()
                    } finally {
                        synchronized(lock) { close() }
                    }
                }
                job?.start()
            }

            // Detach before releasing resources: cancellation can invoke callbacks synchronously.
            fun close() {
                if (attempt === this) attempt = null
                val oldJob = job
                val oldTimeout = syncTimeoutJob
                val oldSocket = socket
                job = null
                syncTimeoutJob = null
                socket = null
                oldTimeout?.cancel()
                oldSocket?.cancel()
                oldJob?.cancel()
            }

            private fun finish(state: ConnectionState) {
                if (!current) return
                close()
                publish(state)
                if (blocked) clearRecoveryOpportunity()
                settled.complete(Unit)
                recoverIfEligible()
            }

            fun subscribe(sessionId: String, fresh: Boolean = false) {
                val currentSocket = socket ?: return
                if (!current || phase == ConnectionState.Connecting) return
                pendingSessions.add(sessionId)
                if (phase is ConnectionState.Online) publish(ConnectionState.Synchronizing)
                armSyncTimeout()
                val cached = cachedSession(paired.host.id, sessionId)
                val command = JSONObject().put("type", "subscribe").put("sessionId", sessionId)
                    .put("historyLimit", InitialHistoryPageSize).put("hasCachedHistory", cached?.savedHistory is SavedHistory.Ready)
                if (!fresh && cached?.serverEpoch != null) command.put("epoch", cached.serverEpoch).put("sequence", cached.wireSequence)
                currentSocket.send(command.toString())
            }

            fun unsubscribe(sessionId: String) {
                if (!current) return
                if (phase != ConnectionState.Connecting) {
                    socket?.send(JSONObject().put("type", "unsubscribe").put("sessionId", sessionId).toString())
                }
                pendingSessions.remove(sessionId)
                completeSynchronization()
            }

            private fun armSyncTimeout() {
                if (syncTimeoutJob != null) return
                syncTimeoutJob = scope.launch {
                    delay(SyncTimeoutMillis)
                    synchronized(lock) {
                        if (isActive && current && phase !is ConnectionState.Online) {
                            finish(ConnectionState.Offline("Connection timed out", ConnectionFailure.TimedOut))
                        }
                    }
                }
            }

            private fun completeSynchronization() {
                if (!current || !hostSynchronized || pendingSessions.isNotEmpty() || phase is ConnectionState.Online) return
                syncTimeoutJob?.cancel()
                syncTimeoutJob = null
                publish(ConnectionState.Online(System.currentTimeMillis()))
                settled.complete(Unit)
            }

            private fun handleFrame(frame: JSONObject) {
                val type = frame.optString("type")
                val sessionId = frame.optString("sessionId")
                if (type in SessionFrames && desiredSessions[paired.host.id]?.contains(sessionId) != true) return
                onFrame(paired.host.id, frame)
                if (!current) return
                when (type) {
                    "host_snapshot", "host_sync" -> hostSynchronized = true
                    "session_snapshot", "session_sync" -> pendingSessions.remove(sessionId)
                }
                completeSynchronization()
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(lock) {
                    if (!current) {
                        webSocket.cancel()
                        return
                    }
                    socket = webSocket
                    gzipNegotiated = response.header("Sec-WebSocket-Protocol") == GzipSocketProtocol
                    publish(ConnectionState.Synchronizing)
                    if (!current) return
                    desiredSessions[paired.host.id]?.toList()?.forEach { subscribe(it) }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                synchronized(lock) {
                    if (!current) return
                    acceptFrame(webSocket, text)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                synchronized(lock) {
                    if (!current) return
                    runCatching { decodeSocketFrame(bytes, gzipNegotiated) }
                        .onSuccess { acceptFrame(webSocket, it) }
                        .onFailure { invalidFrame(webSocket) }
                }
            }

            private fun acceptFrame(webSocket: WebSocket, text: String) {
                runCatching { handleFrame(JSONObject(text)) }
                    .onFailure { invalidFrame(webSocket) }
            }

            private fun invalidFrame(webSocket: WebSocket) {
                webSocket.close(1002, "Invalid protocol frame")
                finish(ConnectionState.Offline("Invalid connection data", ConnectionFailure.InvalidData))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                synchronized(lock) {
                    finish(when (response?.code) {
                        401 -> ConnectionState.AuthenticationRequired
                        426 -> ConnectionState.UpgradeRequired
                        else -> connectionFailure(t, response?.code)
                    })
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                synchronized(lock) {
                    finish(if (code == 1002)
                        ConnectionState.Offline("Invalid connection data", ConnectionFailure.InvalidData)
                    else ConnectionState.Offline("Host closed the connection", ConnectionFailure.HostClosed))
                }
            }
        }
    }

    private companion object {
        const val SyncTimeoutMillis = 15_000L
        val SessionFrames = setOf("session_snapshot", "session_resume", "session_sync", "timeline", "message_patch", "operation")
    }
}

internal fun connectionFailure(error: Throwable, httpCode: Int? = null): ConnectionState.Offline = when {
    httpCode != null -> ConnectionState.Offline("Host rejected the connection (HTTP $httpCode)", ConnectionFailure.HostRejected)
    error is UnknownHostException -> ConnectionState.Offline("Cannot resolve host address", ConnectionFailure.HostNotFound)
    error is SocketTimeoutException -> ConnectionState.Offline("Connection timed out", ConnectionFailure.TimedOut)
    error is ConnectException -> ConnectionState.Offline("Cannot reach host address or port", ConnectionFailure.Unreachable)
    error is SSLException -> ConnectionState.Offline("Secure connection failed", ConnectionFailure.SecureConnectionFailed)
    else -> ConnectionState.Offline("Connection interrupted")
}

