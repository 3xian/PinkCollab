package dev.pinkcollab.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProtocolReducerTest {
    private val key = SessionKey("host", "session")
    private val paired = PairedHost(Host("host", "Desktop", "windows", "18", "1"), "https://host", "secret", "client")
    private fun app() = AppState(hosts = mapOf("host" to HostState(paired)))
    private fun record() = JSONObject("""{"id":"session","hostId":"host","cwd":"/work","title":"Work","createdAt":"2026-01-01","updatedAt":"2026-01-01"}""")
    private fun host() = JSONObject().put("type", "host_snapshot").put("protocolVersion", 3)
        .put("host", paired.host.json()).put("sessions", JSONArray()).put("workspaces", JSONArray())
    private fun runtime(state: String, generation: String = "g") = JSONObject().put("state", state).put("generation", generation)
        .put("pendingInputs", JSONArray())
    private fun session(type: String = "session_snapshot", runtime: JSONObject? = null, history: Boolean = false) = JSONObject()
        .put("type", type).put("sessionId", "session").put("session", record()).put("runtime", runtime ?: JSONObject.NULL)
        .put("hasHistory", history).put("timeline", JSONArray()).put("operations", JSONArray())
    private fun reduce(app: AppState, frame: JSONObject) = reduceProtocol(app, "host", frame, 123)
    private fun item(text: String) = JSONObject().put("id", "item").put("kind", "assistant").put("text", text).put("timestamp", "")
    private fun patch(text: String? = null, remove: Boolean = false, reset: Boolean = false) = JSONObject()
        .put("type", "timeline").put("sessionId", "session").put("reset", reset)
        .put("upsert", JSONArray().also { if (text != null) it.put(item(text)) })
        .put("remove", JSONArray().also { if (remove) it.put("item") })

    @Test fun snapshot_and_reconnect_replace_state_and_invalidate_old_history_requests() {
        val first = reduce(app(), session(runtime = runtime("idle"), history = true)).state
        val detail = first.details.getValue(key)
        val request = HistoryRequest(detail.snapshotToken!!, detail.historyEpoch)
        val reconnect = reduce(first, host()).state
        assertTrue(reconnect.details.isEmpty())
        assertEquals(ConnectionState.Online(123), reconnect.hosts.getValue("host").connection)
        val fresh = reduce(reconnect, session()).state.details.getValue(key)
        assertFalse(request.matches(fresh))
        assertNull(fresh.session.generation)
    }

    @Test fun authoritative_state_replaces_runtime_metadata_model_and_pending_input() {
        var state = reduce(app(), session()).state
        val states = mapOf("starting" to SessionStatus.Starting, "running" to SessionStatus.Running,
            "waiting_input" to SessionStatus.NeedsInput, "stopping" to SessionStatus.Stopping, "idle" to SessionStatus.Idle)
        states.forEach { (wire, expected) ->
            state = reduce(state, session("session_state", runtime(wire))).state
            assertEquals(expected, state.details.getValue(key).session.status)
            assertEquals(expected, state.hosts.getValue("host").sessions.single().status)
        }
        val renamed = session("session_state").also { it.getJSONObject("session").put("title", "New") }
        val detached = reduce(state, renamed).state.details.getValue(key)
        assertEquals("New", detached.session.title)
        assertNull(detached.session.generation)
        assertNull(detached.model)
        assertNull(detached.session.attention)
    }

    @Test fun timeline_upsert_remove_and_reset_leave_saved_history_separate() {
        val start = reduce(app(), session()).state
        val saved = TimelineItem("saved", "user", "older", "", "")
        var state = start.copy(details = mapOf(key to start.details.getValue(key).copy(savedHistory = SavedHistory.Ready("source", listOf(saved), null))))
        state = reduce(state, patch("a")).state
        state = reduce(state, patch("abc")).state
        assertEquals(listOf("abc"), state.details.getValue(key).liveItems.map { it.text })
        state = reduce(state, patch(remove = true)).state
        assertTrue(state.details.getValue(key).liveItems.isEmpty())
        state = reduce(state, patch("new")).state
        state = reduce(state, patch(reset = true)).state
        assertTrue(state.details.getValue(key).liveItems.isEmpty())
        assertEquals(listOf(saved), state.details.getValue(key).savedHistory.items)
    }

    @Test fun operations_replace_by_id_for_all_wire_states() {
        var state = reduce(app(), session()).state
        listOf("pending", "succeeded", "failed", "cancelled", "unknown").forEach { status ->
            val event = JSONObject().put("type", "operation").put("sessionId", "session").put("operation",
                JSONObject().put("id", "command").put("kind", "prompt").put("state", status))
            state = reduce(state, event).state
            assertEquals(OperationStatus.fromWire(status), state.details.getValue(key).operations.single().status)
        }
    }

    @Test fun mapping_and_generation_changes_reload_history_and_cancel_old_requests() {
        val first = reduce(app(), session()).state
        val mapped = reduce(first, session("session_state", runtime("idle"), true))
        assertEquals(listOf(GatewayEffect.LoadHistory(key)), mapped.effects)
        val detail = mapped.state.details.getValue(key)
        val request = HistoryRequest(detail.snapshotToken!!, detail.historyEpoch)
        val exited = reduce(mapped.state, session("session_state", history = true))
        assertFalse(request.matches(exited.state.details.getValue(key)))
        assertEquals(SavedHistory.Loading, exited.state.details.getValue(key).savedHistory)
    }

    @Test fun session_upsert_remove_and_cross_host_identity() {
        val added = reduce(app(), session("session_upsert")).state
        assertEquals("session", added.hosts.getValue("host").sessions.single().id)
        val removed = reduce(added, JSONObject().put("type", "session_remove").put("sessionId", "session")).state
        assertTrue(removed.hosts.getValue("host").sessions.isEmpty())
        assertTrue(removed.details.isEmpty())
        val other = "other"
        val both = app().copy(hosts = app().hosts + (other to HostState(paired.copy(host = paired.host.copy(id = other)))))
        val first = reduce(both, session()).state
        val frame = session().also { it.getJSONObject("session").put("hostId", other).put("title", "Other") }
        val second = reduceProtocol(first, other, frame, 123).state
        assertEquals("Work", second.details.getValue(key).session.title)
        assertEquals("Other", second.details.getValue(SessionKey(other, "session")).session.title)
    }
}
