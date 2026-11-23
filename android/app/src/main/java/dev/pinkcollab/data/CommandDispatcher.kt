package dev.pinkcollab.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Builds Gateway commands and owns durable submission and recovery. */
internal class CommandDispatcher(
    private val state: StateFlow<AppState>,
    private val paired: (String) -> PairedHost,
    private val api: GatewayTransport,
    storage: CommandOutboxStorage,
    private val hostGate: HostCommandGate,
) {
    private val pendingCommands = DurableCommandOutbox(storage)
    private data class RuntimeTarget(val generation: String) {
        fun action(requested: String) = "$requested:generation:$generation"
        fun fields() = JSONObject().put("generation", generation)
    }

    suspend fun removeHost(hostId: String) = pendingCommands.removeHost(hostId)

    suspend fun applyModelSettings(session: Session, model: ModelInfo?, level: String?, fast: Boolean?) {
        val fields = JSONObject().apply {
            session.generation?.let { put("generation", it) }
            model?.let { put("model", JSONObject().put("provider", it.provider).put("id", it.id)) }
            level?.let { put("thinkingLevel", it) }
            fast?.let { put("fastModeEnabled", it) }
        }
        val type = "apply_model_settings"
        val key = SessionKey(session.hostId, session.id)
        hostGate.withHost(session.hostId) {
            val host = paired(session.hostId)
            val transport = commandTransport(host, session.id)
            // Identical settings can be requested again after an intervening change.
            val intentId = UUID.randomUUID().toString()
            val request = CommandRequest(session.hostId, host.clientId, session.id, "$type:intent:$intentId", type, intentId) { fields }
            pendingCommands.submit(request, transport) { commandId ->
                // WS normally wakes us immediately. The receipt endpoint also works
                // if a reconnect or subscription switch dropped the operation event.
                withTimeoutOrNull(60_000) {
                    var receipt: JSONObject
                    do {
                        withTimeoutOrNull(2_000) {
                            state.first { app -> app.details[key]?.operations?.any {
                                it.commandId == commandId && it.status != OperationStatus.Pending
                            } == true }
                        }
                        receipt = transport.lookup(commandId)
                    } while (receipt.getString("state") == "pending")
                    receipt
                } ?: throw IOException("Could not confirm the model settings. Check the session before retrying.")
            }
        }
    }

    suspend fun prompt(hostId: String, id: String, message: String, fileIds: List<String>, intentId: String) =
        command(hostId, id, "prompt", JSONObject().put("message", message).put("fileIds", JSONArray(fileIds)), intentId)

    suspend fun respond(hostId: String, id: String, response: AttentionResponse) =
        command(hostId, id, "respond", response.wire())

    suspend fun command(hostId: String, id: String, command: String, body: JSONObject = JSONObject(), intentId: String? = null) {
        if (command == "prompt") require(!intentId.isNullOrBlank()) { "Prompt intent ID required" }
        val requested = body.toString()
        val target = if (command == "interrupt" || command == "stop" || command == "respond") runtimeTarget(hostId, id) else null
        val action = when {
            intentId != null -> "$command:intent:$intentId"
            // A lookup-only legacy picker start must not swallow a new explicit Start.
            command == "start" -> "start:explicit"
            else -> "$command:$requested"
        }
        sendCommand(hostId, id, target?.action(action) ?: action, when (command) {
            "stop" -> "stop_runtime"
            "start" -> "start_runtime"
            else -> command
        }, intentId) {
            val input = JSONObject(requested)
            when (command) {
                "prompt" -> {
                    val session = currentSession(hostId, id)
                    JSONObject().put("message", input.getString("message"))
                        .also { if (input.has("fileIds")) it.put("fileIds", input.getJSONArray("fileIds")) }
                        .also { if (session?.runtimeAttached == true) it.put("generation", requireNotNull(session.generation) { "Runtime required" }) }
                }
                "start" -> JSONObject()
                "interrupt", "stop" -> requireNotNull(target).fields()
                "respond" -> requireNotNull(target).fields()
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

    private fun currentSession(hostId: String, id: String): Session? {
        val snapshot = state.value
        return snapshot.details[SessionKey(hostId, id)]?.session ?: snapshot.hosts[hostId]?.sessions?.firstOrNull { it.id == id }
    }

    private fun runtimeTarget(hostId: String, id: String) =
        RuntimeTarget(requireNotNull(currentSession(hostId, id)?.generation) { "Runtime required" })

    private suspend fun sendCommand(hostId: String, id: String, action: String, type: String, intentId: String? = null, fields: () -> JSONObject) {
        hostGate.withHost(hostId) {
            val host = paired(hostId)
            pendingCommands.submit(CommandRequest(hostId, host.clientId, id, action, type, intentId, fields), commandTransport(host, id))
        }
    }

    suspend fun recoverPendingCommands() {
        for (pending in pendingCommands.records()) {
            try {
                hostGate.withHost(pending.hostId) {
                    val host = state.value.hosts[pending.hostId]?.paired?.takeIf { it.clientId == pending.clientId }
                        ?: return@withHost
                    pendingCommands.recover(pending, commandTransport(host, pending.sessionId))
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                // Leave the record available for the next restart or the user's retry.
            }
        }
    }

    private fun commandTransport(host: PairedHost, sessionId: String) = CommandTransport(
        post = { body -> JSONObject(api.request(host.url, host.credential, "/api/v4/sessions/$sessionId/commands", "POST", body)) },
        lookup = { commandId -> JSONObject(api.request(host.url, host.credential, "/api/v4/sessions/$sessionId/operations/$commandId")) },
    )
}
