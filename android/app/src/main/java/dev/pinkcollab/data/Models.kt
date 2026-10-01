package dev.pinkcollab.data

import org.json.JSONArray
import org.json.JSONObject

data class Host(val id: String, val name: String, val os: String, val ompVersion: String, val gatewayVersion: String)
data class PairedHost(val host: Host, val url: String, val credential: String, val clientId: String)
data class SessionKey(val hostId: String, val sessionId: String) {
    /** HorizontalPager keys must be Bundle-saveable. The length prefix keeps the two IDs unambiguous. */
    fun pagerKey(): String = "${hostId.length}:$hostId$sessionId"
}
sealed interface AttentionType {
    data object Select : AttentionType
    data object Confirm : AttentionType
    data object Editor : AttentionType
    data class Other(val wire: String) : AttentionType

    companion object {
        fun fromWire(raw: String): AttentionType = when (raw) {
            "select" -> Select
            "confirm" -> Confirm
            "editor" -> Editor
            else -> Other(raw)
        }
    }
}

data class Attention(val id: String, val type: AttentionType, val text: String, val options: List<String>)
data class ModelInfo(
    val provider: String,
    val id: String,
    val name: String,
    val thinkingLevel: String? = null,
    val thinkingLevels: List<String>? = null,
)
data class ModelCatalog(val models: List<ModelInfo>, val thinkingLevels: List<String>)
enum class SessionStatus { Starting, Running, NeedsInput, Stopping, Idle }

enum class SessionOrigin { Managed, Discovered }

data class Session(val id: String, val hostId: String, val cwd: String, val title: String, val status: SessionStatus, val activity: String, val needsAttention: Boolean, val attention: Attention?, val createdAt: String, val updatedAt: String, val runtimeAttached: Boolean, val generation: String? = null, val workTiming: WorkTiming? = null, val origin: SessionOrigin = SessionOrigin.Managed) {
    /** A session is active while its OMP runtime is live, including the startup hand-off. */
    val isActive: Boolean get() = status == SessionStatus.Starting || runtimeAttached
}
/** Gateway sample anchored to the phone's monotonic clock, never its wall clock. */
data class WorkTiming(
    val elapsedMs: Long,
    val running: Boolean,
    val completed: Boolean,
    val receivedAtNanos: Long = System.nanoTime(),
) {
    fun elapsedAt(nowNanos: Long = System.nanoTime()): Long = elapsedMs +
        if (running && !completed) ((nowNanos - receivedAtNanos) / 1_000_000).coerceAtLeast(0) else 0
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
sealed interface OperationStatus {
    data object Pending : OperationStatus
    data object Succeeded : OperationStatus
    data object OutcomeUnknown : OperationStatus
    data object Failed : OperationStatus
    data object Cancelled : OperationStatus
    data class Unknown(val wire: String) : OperationStatus

    companion object {
        fun fromWire(raw: String): OperationStatus = when (raw) {
            "pending" -> Pending
            "succeeded" -> Succeeded
            "unknown" -> OutcomeUnknown
            "failed" -> Failed
            "cancelled" -> Cancelled
            else -> Unknown(raw)
        }
    }
}

data class OperationReceipt(val commandId: String, val status: OperationStatus, val commandType: String, val errorCode: String? = null)
/** Saved transcript for this subscription. Loading and Failed are not an empty page. */
sealed interface SavedHistory {
    val sourceId: String? get() = null
    val items: List<TimelineItem> get() = emptyList()
    val nextCursor: String? get() = null

    /** True only when the transcript is known to be absent. */
    val knownEmpty: Boolean
        get() = when (this) {
            None -> true
            is Ready -> items.isEmpty()
            Loading, Failed -> false
        }

    data object None : SavedHistory
    data object Loading : SavedHistory
    data class Ready(
        override val sourceId: String?,
        override val items: List<TimelineItem>,
        override val nextCursor: String?,
    ) : SavedHistory
    data object Failed : SavedHistory
}

data class SessionDetail(
    val session: Session,
    val streaming: String = "",
    val model: ModelInfo? = null,
    // Local identity for cancelling stale asynchronous reads; never sent over the wire.
    val snapshotToken: String? = null,
    val operations: List<OperationReceipt> = emptyList(),
    val savedHistory: SavedHistory = SavedHistory.None,
    val liveItems: List<TimelineItem> = emptyList(),
    val historyEpoch: Long = 0,
)
data class Workspace(val name: String, val path: String)
data class Listing(val path: String, val parent: String?, val directories: List<Workspace>)
sealed interface ConnectionState {
    data object Connecting : ConnectionState
    data object Synchronizing : ConnectionState
    data class Online(val sinceEpochMillis: Long) : ConnectionState
    data object Reconnecting : ConnectionState
    data class Offline(val reason: String? = null) : ConnectionState
    data object AuthenticationRequired : ConnectionState
    data object UpgradeRequired : ConnectionState
}
data class ConnectionProgress(val attempt: Int, val failure: String? = null)

data class HostState(
    val paired: PairedHost,
    val connection: ConnectionState = ConnectionState.Connecting,
    val sessions: List<Session> = emptyList(),
    val workspaces: List<Workspace> = emptyList(),
    val revision: Long = 0,
    val snapshotToken: String? = null,
    val lastSyncedAtEpochMillis: Long? = null,
    val initialSync: InitialSyncState = InitialSyncState.Pending,
    val connectionProgress: ConnectionProgress? = null,
) {
    val connected: Boolean get() = connection is ConnectionState.Online

    internal fun withConnectionState(connection: ConnectionState, progress: ConnectionProgress?): HostState {
        val initial = when {
            initialSync == InitialSyncState.Ready -> InitialSyncState.Ready
            connection is ConnectionState.Offline ||
                connection == ConnectionState.AuthenticationRequired ||
                connection == ConnectionState.UpgradeRequired -> InitialSyncState.Unavailable
            else -> InitialSyncState.Pending
        }
        return copy(connection = connection, connectionProgress = progress, initialSync = initial)
    }
}
data class AppState(
    val hosts: Map<String, HostState> = emptyMap(),
    val details: Map<SessionKey, SessionDetail> = emptyMap(),
    val loadingCredentials: Boolean = false,
) {
    val taskListLoadState: TaskListLoadState
        get() {
            if (loadingCredentials) return TaskListLoadState.Loading
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

enum class InitialSyncState { Pending, Ready, Unavailable }
enum class TaskListLoadState { Loading, Ready, Unavailable }

fun JSONObject.host() = Host(getString("id"), getString("name"), getString("os"), optString("ompVersion"), optString("gatewayVersion"))
fun Host.json() = JSONObject().put("id", id).put("name", name).put("os", os).put("ompVersion", ompVersion).put("gatewayVersion", gatewayVersion)
fun JSONObject.session(runtime: JSONObject? = null): Session {
    require(optString("origin", "managed") != "discovered" || runtime == null) { "Discovered session has a runtime" }
    val pending = runtime?.optJSONArray("pendingInputs")?.objects().orEmpty()
    val a = pending.firstOrNull()?.let { Attention(it.getString("id"), AttentionType.fromWire(it.getString("type")), it.optString("text"), it.optJSONArray("options")?.strings().orEmpty()) }
    val status = when (runtime?.getString("state")) {
        "starting" -> SessionStatus.Starting
        "stopping" -> SessionStatus.Stopping
        "running" -> SessionStatus.Running
        "waiting_input" -> SessionStatus.NeedsInput
        "idle", null -> SessionStatus.Idle
        else -> error("Unsupported runtime state")
    }
    val activity = runtime?.optString("activity")?.takeIf { it.isNotBlank() } ?: when (status) {
        SessionStatus.Starting -> "Starting OMP"
        SessionStatus.Stopping -> "Stopping OMP"
        SessionStatus.Running -> "Working"
        SessionStatus.NeedsInput -> "Waiting for input"
        SessionStatus.Idle -> if (optString("origin") == "discovered") "History on host" else "Ready to continue"
    }
    return Session(getString("id"), getString("hostId"), getString("cwd"), getString("title"), status, activity, a != null, a, getString("createdAt"), getString("updatedAt"), runtime != null, runtime?.optString("generation")?.takeIf { it.isNotBlank() },
        runtime?.optJSONObject("workTiming")?.takeIf { it.has("elapsedMs") }?.let {
            WorkTiming(it.optLong("elapsedMs").coerceAtLeast(0), it.optBoolean("running"), it.optBoolean("completed"))
        }, when (optString("origin", "managed")) {
            "managed" -> SessionOrigin.Managed
            "discovered" -> SessionOrigin.Discovered
            else -> error("Unsupported session origin")
        })
}
fun JSONObject.sessionSummary(): Session = getJSONObject("session").session(optJSONObject("runtime"))
fun JSONObject.receipt() = OperationReceipt(getString("id"), OperationStatus.fromWire(getString("state")), getString("kind"), optJSONObject("error")?.optString("code"))
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
    thinkingLevels = optJSONArray("thinkingLevels")?.strings(),
)

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
fun JSONObject.workspace() = Workspace(getString("name"), getString("path"))
fun JSONObject.listing() = Listing(
    path = getString("path"),
    parent = optString("parent").takeIf { it.isNotBlank() },
    directories = getJSONArray("directories").objects().map { it.workspace() },
)
