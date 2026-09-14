package dev.pinkcollab.data

import org.json.JSONArray
import org.json.JSONObject

data class Host(val id: String, val name: String, val os: String, val ompVersion: String, val gatewayVersion: String)
data class PairedHost(val host: Host, val url: String, val credential: String, val clientId: String)
data class Attention(val id: String, val type: String, val text: String, val options: List<String>)
data class Session(val id: String, val hostId: String, val cwd: String, val title: String, val status: String, val activity: String, val needsAttention: Boolean, val attention: Attention?, val createdAt: String, val updatedAt: String, val runtimeAttached: Boolean)
data class TimelineItem(val id: String, val kind: String, val text: String, val detail: String, val timestamp: String)
data class SessionDetail(val session: Session, val timeline: List<TimelineItem>, val streaming: String = "")
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
fun JSONObject.item() = TimelineItem(getString("id"), getString("kind"), getString("text"), optString("detail"), getString("timestamp"))
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
fun JSONObject.workspace() = Workspace(getString("name"), getString("path"))
fun JSONObject.listing() = Listing(getString("path"), optString("parent").takeIf { it.isNotBlank() }, getJSONArray("directories").objects().map { it.workspace() }, optJSONObject("git")?.optString("branch"), optJSONObject("git")?.optString("status"))
