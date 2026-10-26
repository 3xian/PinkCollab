package dev.pinkcollab.ui

import dev.pinkcollab.data.Attention
import dev.pinkcollab.data.AttentionType
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.SessionOrigin
import dev.pinkcollab.data.Host
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.PairedHost
import dev.pinkcollab.data.SavedHistory
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SessionDetail
import dev.pinkcollab.data.WorkTiming
import dev.pinkcollab.data.SessionStatus
import dev.pinkcollab.data.TimelineItem
import dev.pinkcollab.data.ToolArguments
import dev.pinkcollab.data.ToolTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionWorkStatusTest {
    private val online = HostState(
        PairedHost(Host("host", "Desktop", "linux", "", ""), "https://host", "secret", "client"),
        connection = ConnectionState.Online(1L),
    )
    private val session = Session(
        "session", "host", "/work", "Work", SessionStatus.Running, "Working", false, null,
        "", "", true, "generation",
    )
    private val user = TimelineItem("user", "user", "Read the source", "", "")
    private fun tool(
        id: String = "read",
        name: String = "functions.read",
        arguments: Map<String, String> = mapOf("i" to "Inspecting session state", "path" to "src/Session.kt"),
        completed: Boolean = false,
    ) = TimelineItem(id, "tool", "", "", "", ToolTrace(id, name, ToolArguments(strings = arguments), "", false, completed))

    @Test fun speed_requires_a_valid_sample_and_online_attached_runtime() {
        val detail = SessionDetail(session.copy(tokensPerSecond = 42.3))
        assertEquals(42.3, sessionWorkStatus(detail, online).tokensPerSecond)
        assertEquals(42.3, sessionWorkStatus(detail.copy(
            session = detail.session.copy(status = SessionStatus.Idle)), online).tokensPerSecond)
        assertEquals(null, sessionWorkStatus(detail, online.copy(connection = ConnectionState.Offline())).tokensPerSecond)
        assertEquals(null, sessionWorkStatus(detail.copy(
            session = detail.session.copy(runtimeAttached = false)), online).tokensPerSecond)
        assertEquals(null, sessionWorkStatus(detail.copy(
            session = detail.session.copy(status = SessionStatus.Starting)), online).tokensPerSecond)
        assertEquals(null, sessionWorkStatus(detail, online, SendProgress.Submitting).tokensPerSecond)
        for (sample in listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(null, sessionWorkStatus(detail.copy(
                session = session.copy(tokensPerSecond = sample)), online).tokensPerSecond)
        }
        assertEquals(0.0, sessionWorkStatus(detail.copy(
            session = session.copy(tokensPerSecond = 0.0)), online).tokensPerSecond)
    }

    @Test fun sending_overrides_attention_and_work_only_while_online() {
        val detail = SessionDetail(
            session.copy(
                attention = Attention("confirm", AttentionType.Confirm, "Apply changes?", emptyList()),
                workTiming = WorkTiming(23_000, true, false, 0),
            ),
            streaming = "Partial answer",
            liveItems = listOf(user, tool()),
        )
        for (progress in listOf(SendProgress.Uploading(1, 2, "notes.txt"), SendProgress.Submitting)) {
            val sending = sessionWorkStatus(detail, online, progress)
            assertEquals(WorkStatusKind.Sending, sending.kind)
            assertFalse(sending.active)
            assertEquals(null, sending.timing)
            val offline = sessionWorkStatus(detail, online.copy(connection = ConnectionState.Offline()), progress)
            assertEquals(WorkStatusKind.Offline, offline.kind)
            assertEquals(null, offline.timing)
        }
        assertEquals(WorkStatusKind.Attention, sessionWorkStatus(detail, online).kind)
    }

    @Test fun discovered_session_never_claims_ready_or_completed_work() {
        val discovered = session.copy(origin = SessionOrigin.Discovered,
            runtimeAttached = false, generation = null, status = SessionStatus.Idle)
        val detail = SessionDetail(discovered, streaming = "Old reply", liveItems = listOf(tool()))
        val status = sessionWorkStatus(detail, online)
        assertEquals(WorkStatusKind.OnHost, status.kind)
        assertFalse(status.active)
        assertEquals(null, status.timing)
        assertEquals(WorkStatusKind.Offline,
            sessionWorkStatus(detail, online.copy(connection = ConnectionState.Offline())).kind)
    }

    @Test fun timing_survives_work_label_changes_but_is_hidden_offline() {
        val timing = WorkTiming(23_000, true, false, 0)
        val detail = SessionDetail(session.copy(workTiming = timing))
        assertEquals(timing, sessionWorkStatus(detail, online).timing)
        assertEquals(timing, sessionWorkStatus(detail.copy(liveItems = listOf(tool())), online).timing)
        assertEquals(null, sessionWorkStatus(detail, online.copy(connection = ConnectionState.Offline())).timing)
        val finished = detail.copy(session = session.copy(status = SessionStatus.Idle, workTiming = WorkTiming(83_000, false, true)))
        assertEquals("Worked for 1m 23s", sessionWorkStatus(finished, online).title)
        assertEquals(null, sessionWorkStatus(finished, online).timing)
    }

    @Test fun elapsed_time_uses_monotonic_samples_and_pauses_for_attention() {
        val timing = WorkTiming(23_000, true, false, 1_000_000_000)
        assertEquals(25_000L, timing.elapsedAt(3_000_000_000))
        assertEquals(23_000L, timing.copy(running = false).elapsedAt(3_000_000_000))
        assertEquals(23_000L, timing.copy(completed = true).elapsedAt(3_000_000_000))
        assertEquals("59s", workDurationLabel(59_999))
        assertEquals("1m 0s", workDurationLabel(60_000))
        val waiting = SessionDetail(session.copy(status = SessionStatus.NeedsInput,
            workTiming = timing.copy(running = false)))
        val status = sessionWorkStatus(waiting, online)
        assertEquals(WorkStatusKind.Attention, status.kind)
        assertEquals(false, status.timing?.running)
    }

    @Test fun connection_loss_overrides_tools_streaming_and_attention() {
        val detail = SessionDetail(
            session.copy(attention = Attention("confirm", AttentionType.Confirm, "Apply changes?", emptyList())),
            streaming = "Here is the answer",
            liveItems = listOf(user, tool()),
        )
        for (connection in listOf(
            ConnectionState.Offline(), ConnectionState.Synchronizing,
            ConnectionState.Connecting, ConnectionState.AuthenticationRequired, ConnectionState.UpgradeRequired,
        )) {
            val status = sessionWorkStatus(detail, online.copy(connection = connection))
            assertEquals(WorkStatusKind.Offline, status.kind)
            assertFalse(status.active)
            assertTrue(!status.connectionHint.isNullOrBlank())
            assertEquals(session.status, detail.session.status)
            assertEquals(session.runtimeAttached, detail.session.runtimeAttached)
        }
        assertEquals(WorkStatusKind.Offline, sessionWorkStatus(detail, null).kind)
    }
    @Test fun recovery_status_distinguishes_network_host_and_explicit_action_blockers() {
        val detail = SessionDetail(session)
        val connections = listOf(
            ConnectionState.Offline(failure = dev.pinkcollab.data.ConnectionFailure.NetworkUnavailable),
            ConnectionState.Offline(failure = dev.pinkcollab.data.ConnectionFailure.Unreachable),
            ConnectionState.Connecting, ConnectionState.Synchronizing,
            ConnectionState.AuthenticationRequired, ConnectionState.UpgradeRequired,
        )
        val statuses = connections.map { sessionWorkStatus(detail, online.copy(connection = it)) }
        assertEquals(connections.size, statuses.map { it.title }.toSet().size)
        assertTrue(statuses.all { it.kind == WorkStatusKind.Offline && !it.active && it.timing == null })
    }


    @Test fun starting_state_never_reuses_cached_active_work() {
        val detail = SessionDetail(
            session.copy(status = SessionStatus.Starting),
            streaming = "Cached reply",
            liveItems = listOf(user, tool()),
        )
        val status = sessionWorkStatus(detail, online)
        assertEquals(WorkStatusKind.Starting, status.kind)
        assertFalse(status.active)
    }

    @Test fun lifecycle_transitions_override_attention_and_tools() {
        for ((phase, kind) in listOf(
            SessionStatus.Starting to WorkStatusKind.Starting,
            SessionStatus.Stopping to WorkStatusKind.Stopping,
        )) {
            val detail = SessionDetail(
                session.copy(status = phase,
                    attention = Attention("confirm", AttentionType.Confirm, "Continue?", emptyList())),
                liveItems = listOf(tool()),
            )
            val status = sessionWorkStatus(detail, online)
            assertEquals(kind, status.kind)
            assertFalse(status.active)
        }
    }

    @Test fun attention_request_precedes_tools_and_streaming() {
        val detail = SessionDetail(
            session.copy(attention = Attention("confirm", AttentionType.Confirm, "Apply these changes?", emptyList())),
            streaming = "Partial answer",
            liveItems = listOf(tool()),
        )
        val status = sessionWorkStatus(detail, online)
        assertEquals(WorkStatusKind.Attention, status.kind)
        assertFalse(status.active)
        for (waiting in listOf(
            session.copy(status = SessionStatus.NeedsInput),
            session.copy(needsAttention = true),
        )) {
            val waitingStatus = sessionWorkStatus(detail.copy(session = waiting), online)
            assertEquals(WorkStatusKind.Attention, waitingStatus.kind)
            assertFalse(waitingStatus.active)
        }
    }

    @Test fun new_user_turn_excludes_unfinished_tools_and_saved_history() {
        val oldTool = tool(id = "old", arguments = mapOf("path" to "stale.kt"))
        val detail = SessionDetail(
            session,
            savedHistory = SavedHistory.Ready("saved", listOf(oldTool), null),
            liveItems = listOf(oldTool, user),
        )
        val thinking = sessionWorkStatus(detail, online)
        assertTrue(thinking.active)
        assertEquals(sessionWorkStatus(SessionDetail(session), online), thinking)

        val current = sessionWorkStatus(detail.copy(liveItems = listOf(oldTool, user, tool())), online)
        assertEquals("Inspecting session state", current.title)
    }

    @Test fun concurrent_tools_count_only_incomplete_calls() {
        val detail = SessionDetail(session, liveItems = listOf(
            user, tool(), tool(id = "finished", completed = true),
            tool(id = "command", name = "bash", arguments = mapOf(
                "description" to "Checking compiler errors", "command" to "./gradlew compileDebugKotlin",
            )),
        ))
        val status = sessionWorkStatus(detail, online)
        assertTrue(status.title.contains("2 tools"))
        assertTrue(status.active)
        val commandFinished = detail.liveItems.last().let { it.copy(tool = it.tool!!.copy(completed = true)) }
        val remaining = sessionWorkStatus(detail.copy(liveItems = detail.liveItems.dropLast(1) + commandFinished), online)
        assertEquals("Inspecting session state", remaining.title)
    }

    @Test fun active_tool_precedes_streaming_until_it_completes() {
        val detail = SessionDetail(session, streaming = "Partial reply", liveItems = listOf(user, tool()))
        assertEquals("Inspecting session state", sessionWorkStatus(detail, online).title)
        val writing = sessionWorkStatus(detail.copy(liveItems = listOf(user, tool(completed = true))), online)
        assertEquals("Writing reply", writing.title)
        assertTrue(writing.active)
        val thinking = sessionWorkStatus(detail.copy(streaming = "", liveItems = listOf(user)), online)
        assertEquals("Thinking", thinking.title)
        assertTrue(thinking.active)
    }

    @Test fun idle_and_detached_are_ready_not_stale_work_or_completion() {
        val detail = SessionDetail(session, streaming = "Old partial reply", liveItems = listOf(tool()))
        for (readySession in listOf(
            session.copy(status = SessionStatus.Idle),
            session.copy(status = SessionStatus.Idle, runtimeAttached = false),
        )) {
            val status = sessionWorkStatus(detail.copy(session = readySession), online)
            assertEquals(WorkStatusKind.Ready, status.kind)
            assertFalse(status.active)
        }
    }
}
