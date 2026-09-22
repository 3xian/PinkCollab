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
    val role: String? = null,
    val thinkingLevel: String? = null,
)
data class Session(val id: String, val hostId: String, val cwd: String, val title: String, val status: String, val activity: String, val needsAttention: Boolean, val attention: Attention?, val createdAt: String, val updatedAt: String, val runtimeAttached: Boolean) {
    /** A task is active while its OMP runtime is live, including the startup hand-off. */
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
data class SessionDetail(val session: Session, val timeline: List<TimelineItem>, val streaming: String = "", val model: ModelInfo? = null)
data class Workspace(val name: String, val path: String)
data class Listing(val path: String, val parent: String?, val directories: List<Workspace>)
sealed interface ConnectionState {
    data object Connecting : ConnectionState
    data object Synchronizing : ConnectionState
    data class Online(val sinceEpochMillis: Long) : ConnectionState
    data class Reconnecting(val attempt: Int, val nextRetryEpochMillis: Long) : ConnectionState
    data class Offline(val reason: String? = null) : ConnectionState
    data object AuthenticationRequired : ConnectionState
}
data class HostState(
    val paired: PairedHost,
    val connection: ConnectionState = ConnectionState.Connecting,
    val sessions: List<Session> = emptyList(),
    val workspaces: List<Workspace> = emptyList(),
    val revision: Long = 0,
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
fun JSONObject.session(): Session {
    val a = optJSONObject("attention")?.let { Attention(it.getString("id"), it.getString("type"), it.getString("text"), it.optJSONArray("options")?.strings().orEmpty()) }
    return Session(getString("id"), getString("hostId"), getString("cwd"), getString("title"), getString("status"), getString("activity"), getBoolean("needsAttention"), a, getString("createdAt"), getString("updatedAt"), optBoolean("runtimeAttached"))
}
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
    role = optString("role").takeIf { it.isNotBlank() },
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
