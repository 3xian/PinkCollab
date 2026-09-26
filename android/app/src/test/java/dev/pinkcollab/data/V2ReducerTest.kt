package dev.pinkcollab.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ReducerTest {
    private fun reduceV2(app: AppState, hostId: String, frame: JSONObject, nowEpochMillis: Long = 123L) =
        dev.pinkcollab.data.reduceV2(app, hostId, frame, nowEpochMillis)

    private val sessionKey = SessionKey("host", "session")
    private val paired = PairedHost(Host("host", "Desktop", "windows", "18.3.1", "0.1.1"), "https://host.example", "secret", "client")
    private fun app() = AppState(hosts = mapOf("host" to HostState(paired)))
    private val record = JSONObject("""{"id":"session","hostId":"host","cwd":"C:/workspace","title":"Work","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}""")
    private fun hostSnapshot(revision: Long = 0) = JSONObject()
        .put("type", "snapshot").put("resource", "host/sessions").put("subscriptionId", "host-sub")
        .put("cursor", JSONObject().put("epoch", "epoch",).put("revision", revision))
        .put("payload", JSONObject().put("protocolVersion", 2).put("host", paired.host.json())
            .put("sessions", org.json.JSONArray().put(JSONObject().put("session", record).put("runtime", JSONObject.NULL)))
            .put("workspaces", org.json.JSONArray()))

    @Test fun same_session_id_on_two_hosts_keeps_details_isolated() {
        val otherHost = "host-b"
        val both = app().copy(hosts = app().hosts + (otherHost to HostState(
            paired.copy(host = paired.host.copy(id = otherHost)),
        )))
        fun snapshot(hostId: String, title: String) = JSONObject()
            .put("type", "snapshot").put("resource", "session/session")
            .put("subscriptionId", "subscription-$hostId")
            .put("cursor", JSONObject().put("epoch", "epoch-$hostId").put("revision", 0))
            .put("payload", JSONObject().put("session", JSONObject(record.toString())
                .put("hostId", hostId).put("title", title))
                .put("runtime", JSONObject.NULL).put("messages", org.json.JSONArray()))
        val first = reduceV2(both, "host", snapshot("host", "A")).state
        val second = reduceV2(first, otherHost, snapshot(otherHost, "B")).state
        val otherKey = SessionKey(otherHost, "session")
        assertEquals(2, second.details.size)
        assertEquals("A", second.details.getValue(sessionKey).session.title)
        assertEquals("B", second.details.getValue(otherKey).session.title)

        val metadata = JSONObject().put("type", "change").put("resource", "session/session")
            .put("subscriptionId", "subscription-host").put("epoch", "epoch-host")
            .put("baseRevision", 0).put("revision", 1)
            .put("changes", org.json.JSONArray().put(JSONObject().put("type", "v2.metadata.updated")
                .put("value", JSONObject().put("session", JSONObject(record.toString()).put("title", "A updated")))))
        val updated = reduceV2(second, "host", metadata).state
        assertEquals("A updated", updated.details.getValue(sessionKey).session.title)
        assertEquals("B", updated.details.getValue(otherKey).session.title)
    }

    @Test fun host_snapshot_uses_supplied_time() {
        val result = reduceV2(app(), "host", hostSnapshot(), nowEpochMillis = 123L).state.hosts.getValue("host")
        assertEquals(ConnectionState.Online(123L), result.connection)
        assertEquals(123L, result.lastSyncedAtEpochMillis)
    }

    @Test fun snapshot_and_ordered_change_update_runtime_without_timestamps() {
        val baseline = reduceV2(app(), "host", hostSnapshot()).state
        val change = JSONObject().put("type", "change").put("resource", "host/sessions")
            .put("subscriptionId", "host-sub").put("epoch", "epoch")
            .put("baseRevision", 0).put("revision", 1)
            .put("changes", org.json.JSONArray().put(JSONObject().put("type", "summary.changed")
                .put("sessionId", "session").put("changeKind", "v2.runtime.updated")
                .put("value", JSONObject().put("runtime", JSONObject().put("generation", "run-one")
                    .put("phase", "ready").put("execution", "active").put("pendingInputs", org.json.JSONArray())))))
        val result = reduceV2(baseline, "host", change)
        assertTrue(result.effects.isEmpty())
        assertEquals(SessionStatus.Running, result.state.hosts.getValue("host").sessions.single().status)
        assertEquals("run-one", result.state.hosts.getValue("host").sessions.single().runtimeGeneration)
        assertEquals(1L, result.state.hosts.getValue("host").cursor?.revision)
    }

    @Test fun gap_and_old_subscription_do_not_replace_view() {
        val baseline = reduceV2(app(), "host", hostSnapshot()).state
        val change = JSONObject().put("type", "change").put("resource", "host/sessions")
            .put("subscriptionId", "host-sub").put("epoch", "epoch")
            .put("baseRevision", 1).put("revision", 2).put("changes", org.json.JSONArray())
        val gap = reduceV2(baseline, "host", change)
        assertEquals(listOf(GatewayEffect.ResyncResource("host", "host/sessions")), gap.effects)
        assertEquals(0L, gap.state.hosts.getValue("host").cursor?.revision)
        change.put("subscriptionId", "old-sub")
        val old = reduceV2(baseline, "host", change)
        assertEquals(listOf(GatewayEffect.ResyncResource("host", "host/sessions")), old.effects)
        assertEquals(0L, old.state.hosts.getValue("host").cursor?.revision)
    }

    @Test fun committed_metadata_change_preserves_the_current_runtime() {
        val baseline = reduceV2(app(), "host", hostSnapshot()).state
        val runtime = JSONObject().put("type", "change").put("resource", "host/sessions")
            .put("subscriptionId", "host-sub").put("epoch", "epoch")
            .put("baseRevision", 0).put("revision", 1)
            .put("changes", org.json.JSONArray().put(JSONObject().put("type", "summary.changed")
                .put("sessionId", "session").put("changeKind", "v2.runtime.updated")
                .put("value", JSONObject().put("runtime", JSONObject().put("generation", "run-one")
                    .put("phase", "ready").put("execution", "active").put("pendingInputs", org.json.JSONArray())))))
        val active = reduceV2(baseline, "host", runtime).state
        val metadata = JSONObject().put("type", "change").put("resource", "host/sessions")
            .put("subscriptionId", "host-sub").put("epoch", "epoch")
            .put("baseRevision", 1).put("revision", 2)
            .put("changes", org.json.JSONArray().put(JSONObject().put("type", "summary.changed")
                .put("sessionId", "session").put("changeKind", "v2.metadata.updated")
                .put("value", JSONObject().put("session", JSONObject(record.toString()).put("title", "Renamed")))))
        val updated = reduceV2(active, "host", metadata).state.hosts.getValue("host").sessions.single()
        assertEquals("Renamed", updated.title)
        assertEquals("run-one", updated.runtimeGeneration)
        assertEquals(SessionStatus.Running, updated.status)
    }

    @Test fun stopping_phase_and_timeline_patches_are_applied_in_order() {
        val hostState = reduceV2(app(), "host", hostSnapshot()).state
        val runtime = JSONObject().put("generation", "run-one").put("phase", "stopping")
            .put("execution", "active").put("pendingInputs", org.json.JSONArray())
        val snapshot = JSONObject().put("type", "snapshot").put("resource", "session/session")
            .put("subscriptionId", "session-sub")
            .put("cursor", JSONObject().put("epoch", "epoch").put("revision", 0))
            .put("payload", JSONObject().put("session", record).put("runtime", runtime)
                .put("messages", org.json.JSONArray()).put("recentOperations", org.json.JSONArray()))
        val baseline = reduceV2(hostState, "host", snapshot).state
        assertEquals(SessionStatus.Stopping, baseline.details.getValue(sessionKey).session.status)

        fun change(base: Long, kind: String, value: JSONObject) = JSONObject()
            .put("type", "change").put("resource", "session/session").put("subscriptionId", "session-sub")
            .put("epoch", "epoch").put("baseRevision", base).put("revision", base + 1)
            .put("changes", org.json.JSONArray().put(JSONObject().put("type", kind).put("value", value)))
        fun item(text: String) = JSONObject().put("id", "message-one").put("kind", "assistant")
            .put("text", text).put("detail", "").put("timestamp", "2026-01-01T00:00:00Z")
        val first = reduceV2(baseline, "host", change(0, "v2.timeline.patch",
            JSONObject().put("items", org.json.JSONArray().put(item("a"))).put("removedIds", org.json.JSONArray()))).state
        val updated = reduceV2(first, "host", change(1, "v2.timeline.patch",
            JSONObject().put("items", org.json.JSONArray().put(item("abc"))).put("removedIds", org.json.JSONArray()))).state
        assertEquals(listOf("abc"), updated.details.getValue(sessionKey).liveItems.map { it.text })
        val reset = reduceV2(updated, "host", change(2, "v2.timeline.reset", JSONObject())).state
        assertEquals(0, reset.details.getValue(sessionKey).liveItems.size)
    }

    @Test fun live_patch_after_loading_saved_history_keeps_the_live_view_separate() {
        val hostState = reduceV2(app(), "host", hostSnapshot()).state
        val runtime = JSONObject().put("generation", "run-one").put("phase", "ready")
            .put("execution", "active").put("pendingInputs", org.json.JSONArray())
        val snapshot = JSONObject().put("type", "snapshot").put("resource", "session/session")
            .put("subscriptionId", "session-sub")
            .put("cursor", JSONObject().put("epoch", "epoch").put("revision", 0))
            .put("payload", JSONObject().put("session", record).put("runtime", runtime)
                .put("messages", org.json.JSONArray()).put("recentOperations", org.json.JSONArray()))
        val baseline = reduceV2(hostState, "host", snapshot).state
        val history = TimelineItem("saved", "user", "older", "", "")
        val detail = baseline.details.getValue(sessionKey).copy(historyItems = listOf(history))
        val withHistory = baseline.copy(details = baseline.details + (sessionKey to detail))
        val live = JSONObject().put("id", "live").put("kind", "assistant")
            .put("text", "current").put("detail", "").put("timestamp", "")
        val patch = JSONObject().put("type", "change").put("resource", "session/session")
            .put("subscriptionId", "session-sub").put("epoch", "epoch")
            .put("baseRevision", 0).put("revision", 1)
            .put("changes", org.json.JSONArray().put(JSONObject().put("type", "v2.timeline.patch")
                .put("value", JSONObject().put("items", org.json.JSONArray().put(live))
                    .put("removedIds", org.json.JSONArray()))))
        val updated = reduceV2(withHistory, "host", patch).state.details.getValue(sessionKey)
        assertEquals(listOf("saved"), updated.historyItems.map { it.id })
        assertEquals(listOf("live"), updated.liveItems.map { it.id })
    }

    @Test fun runtime_exit_clears_previous_history_until_the_new_page_arrives() {
        val hostState = reduceV2(app(), "host", hostSnapshot()).state
        val runtime = JSONObject().put("generation", "run-one").put("phase", "ready")
            .put("execution", "quiescent").put("pendingInputs", org.json.JSONArray())
        val snapshot = JSONObject().put("type", "snapshot").put("resource", "session/session")
            .put("subscriptionId", "session-sub")
            .put("cursor", JSONObject().put("epoch", "epoch").put("revision", 0))
            .put("payload", JSONObject().put("session", record).put("runtime", runtime)
                .put("messages", org.json.JSONArray()).put("recentOperations", org.json.JSONArray()))
        val baseline = reduceV2(hostState, "host", snapshot).state
        val oldHistory = TimelineItem("saved", "user", "older", "", "")
        val detail = baseline.details.getValue(sessionKey).copy(
            historyItems = listOf(oldHistory), historySourceId = "old-source", nextHistoryCursor = "older",
        )
        val withHistory = baseline.copy(details = baseline.details + (sessionKey to detail))
        val exit = JSONObject().put("type", "change").put("resource", "session/session")
            .put("subscriptionId", "session-sub").put("epoch", "epoch")
            .put("baseRevision", 0).put("revision", 1)
            .put("changes", org.json.JSONArray().put(JSONObject().put("type", "v2.runtime.exited")
                .put("value", JSONObject())))
        val result = reduceV2(withHistory, "host", exit)
        assertEquals(listOf(GatewayEffect.LoadHistory(sessionKey)), result.effects)
        assertEquals(emptyList<TimelineItem>(), result.state.details.getValue(sessionKey).historyItems)
        assertNull(result.state.details.getValue(sessionKey).historySourceId)
        assertNull(result.state.details.getValue(sessionKey).nextHistoryCursor)

        val previousRequest = HistoryRequest("session-sub", result.state.details.getValue(sessionKey).historyEpoch)
        val nextRuntime = JSONObject().put("generation", "run-two").put("phase", "ready")
            .put("execution", "quiescent").put("pendingInputs", org.json.JSONArray())
        val attach = JSONObject().put("type", "change").put("resource", "session/session")
            .put("subscriptionId", "session-sub").put("epoch", "epoch")
            .put("baseRevision", 1).put("revision", 2)
            .put("changes", org.json.JSONArray().put(JSONObject().put("type", "v2.runtime.updated")
                .put("value", JSONObject().put("runtime", nextRuntime))))
        val secondRuntime = reduceV2(result.state, "host", attach).state
        val secondExit = JSONObject().put("type", "change").put("resource", "session/session")
            .put("subscriptionId", "session-sub").put("epoch", "epoch")
            .put("baseRevision", 2).put("revision", 3)
            .put("changes", org.json.JSONArray().put(JSONObject().put("type", "v2.runtime.exited")
                .put("value", JSONObject())))
        val latest = reduceV2(secondRuntime, "host", secondExit).state.details.getValue(sessionKey)
        assertNull(latest.session.runtimeGeneration)
        assertTrue(!previousRequest.matches(latest))
    }
}
