package dev.pinkcollab.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ReducerTest {
    private val paired = PairedHost(Host("host", "Desktop", "windows", "18.3.1", "0.1.1"), "https://host.example", "secret", "client")
    private fun app() = AppState(hosts = mapOf("host" to HostState(paired)))
    private val record = JSONObject("""{"id":"session","hostId":"host","cwd":"C:/workspace","title":"Work","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}""")
    private fun hostSnapshot(revision: Long = 0) = JSONObject()
        .put("type", "snapshot").put("resource", "host/sessions").put("subscriptionId", "host-sub")
        .put("cursor", JSONObject().put("epoch", "epoch",).put("revision", revision))
        .put("payload", JSONObject().put("protocolVersion", 2).put("host", paired.host.json())
            .put("sessions", org.json.JSONArray().put(JSONObject().put("session", record).put("runtime", JSONObject.NULL)))
            .put("workspaces", org.json.JSONArray()))

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
        assertNull(result.resyncResource)
        assertEquals("running", result.state.hosts.getValue("host").sessions.single().status)
        assertEquals("run-one", result.state.hosts.getValue("host").sessions.single().runtimeGeneration)
        assertEquals(1L, result.state.hosts.getValue("host").cursor?.revision)
    }

    @Test fun gap_and_old_subscription_do_not_replace_view() {
        val baseline = reduceV2(app(), "host", hostSnapshot()).state
        val change = JSONObject().put("type", "change").put("resource", "host/sessions")
            .put("subscriptionId", "host-sub").put("epoch", "epoch")
            .put("baseRevision", 1).put("revision", 2).put("changes", org.json.JSONArray())
        val gap = reduceV2(baseline, "host", change)
        assertEquals("host/sessions", gap.resyncResource)
        assertEquals(0L, gap.state.hosts.getValue("host").cursor?.revision)
        change.put("subscriptionId", "old-sub")
        val old = reduceV2(baseline, "host", change)
        assertEquals("host/sessions", old.resyncResource)
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
        assertEquals("running", updated.status)
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
        assertEquals("stopping", baseline.details.getValue("session").session.status)

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
        assertEquals(listOf("abc"), updated.details.getValue("session").liveItems.map { it.text })
        val reset = reduceV2(updated, "host", change(2, "v2.timeline.reset", JSONObject())).state
        assertEquals(0, reset.details.getValue("session").liveItems.size)
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
        val detail = baseline.details.getValue("session").copy(historyItems = listOf(history))
        val withHistory = baseline.copy(details = baseline.details + ("session" to detail))
        val live = JSONObject().put("id", "live").put("kind", "assistant")
            .put("text", "current").put("detail", "").put("timestamp", "")
        val patch = JSONObject().put("type", "change").put("resource", "session/session")
            .put("subscriptionId", "session-sub").put("epoch", "epoch")
            .put("baseRevision", 0).put("revision", 1)
            .put("changes", org.json.JSONArray().put(JSONObject().put("type", "v2.timeline.patch")
                .put("value", JSONObject().put("items", org.json.JSONArray().put(live))
                    .put("removedIds", org.json.JSONArray()))))
        val updated = reduceV2(withHistory, "host", patch).state.details.getValue("session")
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
        val detail = baseline.details.getValue("session").copy(
            historyItems = listOf(oldHistory), historySourceId = "old-source", nextHistoryCursor = "older",
        )
        val withHistory = baseline.copy(details = baseline.details + ("session" to detail))
        val exit = JSONObject().put("type", "change").put("resource", "session/session")
            .put("subscriptionId", "session-sub").put("epoch", "epoch")
            .put("baseRevision", 0).put("revision", 1)
            .put("changes", org.json.JSONArray().put(JSONObject().put("type", "v2.runtime.exited")
                .put("value", JSONObject())))
        val result = reduceV2(withHistory, "host", exit)
        assertEquals("session", result.historySessionId)
        assertEquals(emptyList<TimelineItem>(), result.state.details.getValue("session").historyItems)
        assertNull(result.state.details.getValue("session").historySourceId)
        assertNull(result.state.details.getValue("session").nextHistoryCursor)

        val previousRequest = HistoryRequest("session-sub", result.state.details.getValue("session").historyEpoch)
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
        val latest = reduceV2(secondRuntime, "host", secondExit).state.details.getValue("session")
        assertNull(latest.session.runtimeGeneration)
        assertTrue(!previousRequest.matches(latest))
    }
}
