package dev.pinkcollab.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class SessionDraft(
    val text: String = "",
    val files: List<SelectedFile> = emptyList(),
    val version: Long = 0,
    val intentId: String = UUID.randomUUID().toString(),
    val sendStarted: Boolean = false,
)

internal class SessionDraftStore(
    initial: Map<SessionKey, SessionDraft> = emptyMap(),
) {
    private val mutable = MutableStateFlow(initial)
    val state = mutable.asStateFlow()
    private var lastVersion = initial.values.maxOfOrNull { it.version } ?: 0L
    private fun nextVersion() = ++lastVersion

    fun markSendStarted(key: SessionKey, version: Long) {
        mutable.value[key]?.takeIf { it.version == version && !it.sendStarted }?.let {
            mutable.value = mutable.value + (key to it.copy(sendStarted = true))
        }
    }

    fun rotateFailedIntent(key: SessionKey, version: Long, intentId: String) {
        val current = mutable.value[key] ?: return
        if (current.version != version || current.intentId != intentId) return
        mutable.value = mutable.value + (key to current.copy(intentId = UUID.randomUUID().toString(), sendStarted = false))
    }

    private fun nextIntent(current: SessionDraft): String =
        if (current.sendStarted) UUID.randomUUID().toString() else current.intentId

    fun setText(key: SessionKey, text: String) = change(key) { current ->
        if (current.text == text) current else current.copy(text = text, version = nextVersion(), intentId = nextIntent(current), sendStarted = false)
    }

    fun addFile(key: SessionKey, file: SelectedFile) = change(key) { current ->
        if (current.files.size >= 5 || current.files.any { it.uri == file.uri }) current
        else current.copy(files = current.files + file, version = nextVersion(), intentId = nextIntent(current), sendStarted = false)
    }

    fun removeFile(key: SessionKey, fileId: String) = change(key) { current ->
        val remaining = current.files.filterNot { it.id == fileId }
        if (remaining.size == current.files.size) current
        else current.copy(files = remaining, version = nextVersion(), intentId = nextIntent(current), sendStarted = false)
    }

    fun clearIfVersion(key: SessionKey, version: Long) {
        if (mutable.value[key]?.version == version) {
            mutable.value = mutable.value - key
        }
    }

    fun removeHost(hostId: String) {
        val next = mutable.value.filterKeys { it.hostId != hostId }
        if (next != mutable.value) mutable.value = next
    }

    private fun change(key: SessionKey, transform: (SessionDraft) -> SessionDraft) {
        val current = mutable.value[key] ?: SessionDraft()
        val next = transform(current)
        if (next == current) return
        mutable.value = if (next.text.isEmpty() && next.files.isEmpty()) mutable.value - key else mutable.value + (key to next)
    }
}

internal fun encodeDrafts(drafts: Map<SessionKey, SessionDraft>): String = JSONArray().apply {
    drafts.forEach { (key, draft) ->
        put(JSONObject().put("hostId", key.hostId).put("sessionId", key.sessionId)
            .put("text", draft.text).put("version", draft.version).put("intentId", draft.intentId)
            .put("sendStarted", draft.sendStarted)
            .put("files", JSONArray().apply {
                draft.files.forEach { file ->
                    put(JSONObject().put("uri", file.uri).put("name", file.name).put("id", file.id))
                }
            }))
    }
}.toString()

internal fun decodeDrafts(raw: String?): Map<SessionKey, SessionDraft> = runCatching {
    if (raw.isNullOrBlank()) return@runCatching emptyMap()
    val array = JSONArray(raw)
    (0 until array.length()).associate { index ->
        val entry = array.getJSONObject(index)
        val key = SessionKey(entry.getString("hostId"), entry.getString("sessionId"))
        val files = entry.getJSONArray("files")
        key to SessionDraft(
            text = entry.getString("text"),
            files = (0 until files.length()).map { fileIndex ->
                files.getJSONObject(fileIndex).let { SelectedFile(it.getString("uri"), it.getString("name"), it.getString("id")) }
            },
            version = entry.getLong("version"),
            intentId = entry.getString("intentId"),
            sendStarted = entry.optBoolean("sendStarted", false),
        )
    }
}.getOrDefault(emptyMap())
