package dev.pinkcollab.data

import org.json.JSONArray
import org.json.JSONObject

data class Host(val id: String, val name: String, val os: String, val ompVersion: String, val gatewayVersion: String)
data class PairedHost(val host: Host, val url: String, val credential: String, val clientId: String)
data class Attention(val id: String, val type: String, val text: String, val options: List<String>)
data class ModelInfo(val provider: String, val id: String, val name: String)
data class Session(val id: String, val hostId: String, val cwd: String, val title: String, val status: String, val activity: String, val needsAttention: Boolean, val attention: Attention?, val createdAt: String, val updatedAt: String, val runtimeAttached: Boolean)
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
data class Listing(val path: String, val parent: String?, val directories: List<Workspace>, val branch: String?, val gitStatus: String?)
data class HostState(val paired: PairedHost, val connected: Boolean = false, val sessions: List<Session> = emptyList(), val workspaces: List<Workspace> = emptyList(), val revision: Long = 0)
data class AppState(val hosts: Map<String, HostState> = emptyMap(), val details: Map<String, SessionDetail> = emptyMap(), val loading: Boolean = false, val error: String? = null)

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
fun JSONObject.modelInfo() = ModelInfo(getString("provider"), getString("id"), getString("name"))

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
fun JSONObject.workspace() = Workspace(getString("name"), getString("path"))
fun JSONObject.listing() = Listing(getString("path"), optString("parent").takeIf { it.isNotBlank() }, getJSONArray("directories").objects().map { it.workspace() }, optJSONObject("git")?.optString("branch"), optJSONObject("git")?.optString("status"))
