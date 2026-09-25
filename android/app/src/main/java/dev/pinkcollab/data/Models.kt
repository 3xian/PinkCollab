package dev.pinkcollab.data

import org.json.JSONArray
import org.json.JSONObject

data class Host(val id: String, val name: String, val os: String, val ompVersion: String, val gatewayVersion: String)
data class PairedHost(val host: Host, val url: String, val credential: String, val clientId: String)
data class Attention(val id: String, val type: String, val text: String, val options: List<String>)
data class ModelInfo(
    val provider: String,
    val id: String,
    val name: String,
    val thinkingLevel: String? = null,
)
data class ModelCatalog(val models: List<ModelInfo>, val thinkingLevels: List<String>)
data class Session(val id: String, val hostId: String, val cwd: String, val title: String, val status: String, val activity: String, val needsAttention: Boolean, val attention: Attention?, val createdAt: String, val updatedAt: String, val runtimeAttached: Boolean, val runtimeGeneration: String? = null, val runtimeExecution: String = "unknown") {
    /** A session is active while its OMP runtime is live, including the startup hand-off. */
    val isActive: Boolean get() = status == "starting" || runtimeAttached
}
data class ToolArguments(
    val raw: String = "",
    val strings: Map<String, String> = emptyMap(),
    val stringLists: Map<String, List<String>> = emptyMap(),
) {
    companion object {
        fun from(json: JSONObject): ToolArguments {
            val strings = mutableMapOf<String, String>()
            val stringLists = mutableMapOf<String, List<String>>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                when (val value = json.opt(key)) {
                    is String -> strings[key] = value
                    // Entries that are not strings stay reachable through `raw`; keeping whatever
                    // strings an array does hold stops `{"files":["a",{"path":"b"}]}` from losing
                    // the file it names.
                    is JSONArray -> stringLists[key] = (0 until value.length()).mapNotNull { value.opt(it) as? String }
                }
            }
            return ToolArguments(json.toString(), strings, stringLists)
        }

        /**
         * `arguments` is a JSON object on the wire. A string holding the same JSON is accepted too,
         * so this parsing detail stays here instead of leaking into the UI; anything that is not a
         * JSON object (absent, `null`, malformed) means the call carried no arguments.
         */
        fun parse(raw: String): ToolArguments =
            runCatching { from(JSONObject(raw)) }.getOrElse { ToolArguments() }
    }
}
data class ToolTrace(val callId: String, val name: String, val arguments: ToolArguments, val result: String, val isError: Boolean, val completed: Boolean)
data class TimelineItem(val id: String, val kind: String, val text: String, val detail: String, val timestamp: String, val tool: ToolTrace? = null)
data class OperationReceipt(val commandId: String, val status: String, val commandType: String, val errorCode: String? = null)
data class SessionDetail(
    val session: Session,
    val timeline: List<TimelineItem>,
    val streaming: String = "",
    val model: ModelInfo? = null,
    val cursor: Cursor? = null,
    val subscriptionId: String? = null,
    val operations: List<OperationReceipt> = emptyList(),
    val historySourceId: String? = null,
    val nextHistoryCursor: String? = null,
    val historyItems: List<TimelineItem> = emptyList(),
    val liveItems: List<TimelineItem> = emptyList(),
)
data class Cursor(val epoch: String, val revision: Long) {
    fun accepts(epoch: String, baseRevision: Long, revision: Long): Boolean =
        this.epoch == epoch && baseRevision == this.revision && revision > this.revision
}
data class Workspace(val name: String, val path: String)
data class Listing(val path: String, val parent: String?, val directories: List<Workspace>)
sealed interface ConnectionState {
    data object Connecting : ConnectionState
    data object Synchronizing : ConnectionState
    data class Online(val sinceEpochMillis: Long) : ConnectionState
    data class Reconnecting(val attempt: Int, val nextRetryEpochMillis: Long) : ConnectionState
    data class Offline(val reason: String? = null) : ConnectionState
    data object AuthenticationRequired : ConnectionState
    data object UpgradeRequired : ConnectionState
}
data class HostState(
    val paired: PairedHost,
    val connection: ConnectionState = ConnectionState.Connecting,
    val sessions: List<Session> = emptyList(),
    val workspaces: List<Workspace> = emptyList(),
    val revision: Long = 0,
    val cursor: Cursor? = null,
    val subscriptionId: String? = null,
    val lastSyncedAtEpochMillis: Long? = null,
    val initialSync: InitialSyncState = InitialSyncState.Pending,
) {
    val connected: Boolean get() = connection is ConnectionState.Online
}
data class AppState(
    val hosts: Map<String, HostState> = emptyMap(),
    val details: Map<String, SessionDetail> = emptyMap(),
    val error: String? = null,
) {
    val taskListLoadState: TaskListLoadState
        get() {
            val states = hosts.values
            if (states.isEmpty() || states.any { it.sessions.isNotEmpty() }) {
                return TaskListLoadState.Ready
            }
            if (states.all { it.initialSync == InitialSyncState.Ready }) {
                return TaskListLoadState.Ready
            }
            return if (states.any { it.initialSync == InitialSyncState.Pending }) {
                TaskListLoadState.Loading
            } else {
                TaskListLoadState.Unavailable
            }
        }
}

internal const val InitialSyncTimeoutMillis = 8_000L

enum class InitialSyncState { Pending, Ready, Unavailable }
enum class TaskListLoadState { Loading, Ready, Unavailable }

fun JSONObject.host() = Host(getString("id"), getString("name"), getString("os"), optString("ompVersion"), optString("gatewayVersion"))
fun Host.json() = JSONObject().put("id", id).put("name", name).put("os", os).put("ompVersion", ompVersion).put("gatewayVersion", gatewayVersion)
fun JSONObject.session(runtime: JSONObject? = null): Session {
    val pending = runtime?.optJSONArray("pendingInputs")?.objects().orEmpty()
    val a = pending.firstOrNull()?.let { Attention(it.getString("id"), it.getString("type"), it.optString("text"), it.optJSONArray("options")?.strings().orEmpty()) }
    val phase = runtime?.optString("phase") ?: ""
    val execution = runtime?.optString("execution") ?: "unknown"
    val status = when {
        phase == "starting" -> "starting"
        phase == "stopping" -> "stopping"
        a != null -> "needs_input"
        execution == "active" -> "running"
        else -> "idle"
    }
    val activity = runtime?.optString("activity")?.takeIf { it.isNotBlank() } ?: when (status) {
        "starting" -> "Starting OMP"
        "stopping" -> "Stopping OMP"
        "running" -> "Working"
        "needs_input" -> "Waiting for input"
        else -> "Ready to continue"
    }
    return Session(getString("id"), getString("hostId"), getString("cwd"), getString("title"), status, activity, a != null, a, getString("createdAt"), getString("updatedAt"), runtime != null, runtime?.optString("generation")?.takeIf { it.isNotBlank() }, execution)
}
fun JSONObject.sessionSummary(): Session = getJSONObject("session").session(optJSONObject("runtime"))
fun JSONObject.cursor() = Cursor(getString("epoch"), getLong("revision"))
fun JSONObject.receipt() = OperationReceipt(getString("commandId"), getString("status"), getString("commandType"), optJSONObject("error")?.optString("code"))
fun Session.withRuntime(runtime: JSONObject?): Session = JSONObject()
    .put("id", id).put("hostId", hostId).put("cwd", cwd).put("title", title)
    .put("createdAt", createdAt).put("updatedAt", updatedAt).session(runtime)
fun JSONObject.item(): TimelineItem {
    val tool = optJSONObject("tool")?.let {
        val arguments = when (val value = it.opt("arguments")) {
            is JSONObject -> ToolArguments.from(value)
            is String -> ToolArguments.parse(value)
            else -> ToolArguments()
        }
        ToolTrace(
            callId = it.optString("callId", getString("id")),
            name = it.optString("name"),
            arguments = arguments,
            result = it.optString("result"),
            isError = it.optBoolean("isError"),
            completed = it.optBoolean("completed"),
        )
    }
    return TimelineItem(getString("id"), getString("kind"), getString("text"), optString("detail"), getString("timestamp"), tool)
}
fun JSONObject.modelInfo() = ModelInfo(
    provider = getString("provider"),
    id = getString("id"),
    name = getString("name"),
    thinkingLevel = optString("thinkingLevel").takeIf { it.isNotBlank() },
)

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
fun JSONObject.workspace() = Workspace(getString("name"), getString("path"))
fun JSONObject.listing() = Listing(
    path = getString("path"),
    parent = optString("parent").takeIf { it.isNotBlank() },
    directories = getJSONArray("directories").objects().map { it.workspace() },
)
