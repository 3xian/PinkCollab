package dev.pinkcollab.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

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
        fun fields() = JSONObject().put("expectedGeneration", generation)
    }

    suspend fun removeHost(hostId: String) = pendingCommands.removeHost(hostId)

    suspend fun selectModel(hostId: String, id: String, model: ModelInfo) {
        val target = runtimeTarget(hostId, id)
        sendCommand(hostId, id, target.action("select_model:${model.provider}:${model.id}"), "select_model") {
            target.fields().put("provider", model.provider).put("modelId", model.id)
        }
    }

    suspend fun setThinkingLevel(hostId: String, id: String, level: String) {
        val target = runtimeTarget(hostId, id)
        sendCommand(hostId, id, target.action("set_thinking_level:$level"), "set_thinking_level") {
            target.fields().put("level", level)
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
        val action = intentId?.let { "$command:intent:$it" } ?: "$command:$requested"
        sendCommand(hostId, id, target?.action(action) ?: action, when (command) {
            "stop" -> "stop_runtime"
            "start" -> "start_runtime"
            else -> command
        }, intentId) {
            val input = JSONObject(requested)
            when (command) {
                "prompt" -> {
                    val session = currentSession(hostId, id)
                    val delivery = if (session?.runtimeExecution == RuntimeExecution.Active) "steer" else "start"
                    JSONObject().put("delivery", delivery).put("message", input.getString("message"))
                        .also { if (input.has("fileIds")) it.put("fileIds", input.getJSONArray("fileIds")) }
                        .also { if (session?.runtimeAttached == true) it.put("expectedGeneration", requireNotNull(session.runtimeGeneration) { "Runtime required" }) }
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
        RuntimeTarget(requireNotNull(currentSession(hostId, id)?.runtimeGeneration) { "Runtime required" })

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
        post = { body -> JSONObject(api.request(host.url, host.credential, "/api/v2/sessions/$sessionId/commands", "POST", body)) },
        lookup = { commandId -> JSONObject(api.request(host.url, host.credential, "/api/v2/sessions/$sessionId/operations/$commandId")) },
    )
}
