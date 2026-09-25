package dev.pinkcollab.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
