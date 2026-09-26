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

    suspend fun removeHost(hostId: String) = pendingCommands.removeHost(hostId)

    suspend fun selectModel(hostId: String, id: String, model: ModelInfo) {
        sendCommand(hostId, id, "select_model:${model.provider}:${model.id}", "select_model") {
            val generation = requireNotNull(state.value.details[SessionKey(hostId, id)]?.session?.runtimeGeneration) { "Runtime required" }
            JSONObject().put("expectedGeneration", generation).put("provider", model.provider).put("modelId", model.id)
        }
    }

    suspend fun setThinkingLevel(hostId: String, id: String, level: String) {
        sendCommand(hostId, id, "set_thinking_level:$level", "set_thinking_level") {
            val generation = requireNotNull(state.value.details[SessionKey(hostId, id)]?.session?.runtimeGeneration) { "Runtime required" }
            JSONObject().put("expectedGeneration", generation).put("level", level)
        }
    }

    suspend fun prompt(hostId: String, id: String, message: String, fileIds: List<String>, intentId: String) =
        command(hostId, id, "prompt", JSONObject().put("message", message).put("fileIds", JSONArray(fileIds)), intentId)

    suspend fun respond(hostId: String, id: String, response: AttentionResponse) =
        command(hostId, id, "respond", response.wire())

    suspend fun command(hostId: String, id: String, command: String, body: JSONObject = JSONObject(), intentId: String? = null) {
        if (command == "prompt") require(!intentId.isNullOrBlank()) { "Prompt intent ID required" }
        val requested = body.toString()
        sendCommand(hostId, id, intentId?.let { "$command:intent:$it" } ?: "$command:$requested", when (command) {
            "stop" -> "stop_runtime"
            "start" -> "start_runtime"
            else -> command
        }, intentId) {
            val input = JSONObject(requested)
            val session = state.value.details[SessionKey(hostId, id)]?.session ?: state.value.hosts[hostId]?.sessions?.firstOrNull { it.id == id }
            val generation = session?.runtimeGeneration
            when (command) {
                "prompt" -> {
                    val delivery = if (session?.runtimeExecution == RuntimeExecution.Active) "steer" else "start"
                    JSONObject().put("delivery", delivery).put("message", input.getString("message"))
                        .also { if (input.has("fileIds")) it.put("fileIds", input.getJSONArray("fileIds")) }
                        .also { if (session?.runtimeAttached == true) it.put("expectedGeneration", requireNotNull(generation) { "Runtime required" }) }
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
