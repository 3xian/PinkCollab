package dev.pinkcollab.ui

import dev.pinkcollab.data.AppState
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.Host
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.InitialSyncState
import dev.pinkcollab.data.PairedHost
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SessionKey
import dev.pinkcollab.data.SessionStatus
import dev.pinkcollab.data.TaskListLoadState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HostSessionFilterTest {
    @Test fun options_use_phone_names_and_disambiguate_duplicates() {
        val hosts = mapOf(
            "zeta" to host("zeta", "Gateway", "Zed"),
            "alpha" to host("alpha", "Gateway", "  "),
            "office" to host("office", "Gateway", "Office"),
            "desk" to host("desk", "Desktop", "Office"),
        )
        assertEquals(
            listOf(
                HostFilterOption("alpha", "alpha"),
                HostFilterOption("desk", "Office · desk"),
                HostFilterOption("office", "Office · office"),
                HostFilterOption("zeta", "Zed"),
            ),
            hostFilterOptions(hosts),
        )
    }

    @Test fun filter_keeps_presentation_order_and_drops_other_hosts() {
        val olderActive = session("older", "a", active = true, created = "2026-01-02T00:00:00Z", updated = "2026-01-09T00:00:00Z")
        val newerActive = session("newer", "b", active = true, created = "2026-01-03T00:00:00Z", updated = "2026-01-04T00:00:00Z")
        val recentIdle = session("recent", "a", active = false, created = "2026-01-01T00:00:00Z", updated = "2026-01-08T00:00:00Z")
        val staleIdle = session("stale", "b", active = false, created = "2026-01-06T00:00:00Z", updated = "2026-01-07T00:00:00Z")
        val hosts = mapOf(
            "a" to host("a", "A", sessions = listOf(recentIdle, olderActive)),
            "b" to host("b", "B", sessions = listOf(staleIdle, newerActive)),
        )
        assertEquals(listOf("newer", "older", "recent", "stale"), sessionsForHostFilter(hosts, null).map { it.id })
        assertEquals(listOf("older", "recent"), sessionsForHostFilter(hosts, "a").map { it.id })
        assertEquals(emptyList<String>(), sessionsForHostFilter(hosts, "missing").map { it.id })
    }

    @Test fun hidden_selection_moves_to_the_first_visible_session_and_an_empty_host_keeps_it() {
        val visible = listOf(
            session("first", "a", active = true, created = "2026-01-03T00:00:00Z", updated = "2026-01-03T00:00:00Z"),
            session("second", "a", active = false, created = "2026-01-01T00:00:00Z", updated = "2026-01-02T00:00:00Z"),
        )
        assertNull(sessionToSelectForHostFilter(SessionKey("a", "second"), visible))
        assertEquals(SessionKey("a", "first"), sessionToSelectForHostFilter(SessionKey("b", "other"), visible))
        assertNull(sessionToSelectForHostFilter(SessionKey("b", "other"), emptyList()))
    }

    @Test fun phone_name_changes_emit_new_filter_labels() = runTest {
        val original = host("a", "Gateway", "Office")
        val renamed = original.copy(paired = original.paired.copy(localName = "Desk"))
        val before = sessionListState(AppState(hosts = mapOf("a" to original)))
        val after = sessionListState(AppState(hosts = mapOf("a" to renamed)))

        val emitted = flowOf(before, before, after)
            .distinctUntilChanged(SessionListState::samePresentation)
            .toList()

        assertEquals(listOf(before, after), emitted)
        assertEquals(listOf("Office", "Desk"), emitted.map { hostFilterOptions(it.hosts).single().label })
    }

    @Test fun gateway_name_changes_emit_when_no_phone_name_is_set() = runTest {
        val original = host("a", "Gateway")
        val renamed = original.copy(paired = original.paired.copy(host = original.paired.host.copy(name = "Desktop")))
        val before = sessionListState(AppState(hosts = mapOf("a" to original)))
        val after = sessionListState(AppState(hosts = mapOf("a" to renamed)))

        val emitted = flowOf(before, after)
            .distinctUntilChanged(SessionListState::samePresentation)
            .toList()

        assertEquals(listOf(before, after), emitted)
        assertEquals(listOf("Gateway", "Desktop"), emitted.map { hostFilterOptions(it.hosts).single().label })
    }

    @Test fun pending_host_stays_loading_when_another_host_has_sessions() {
        val pending = host("b", "B")
        val all = stateWithPopulatedHost(pending)

        assertEquals(TaskListLoadState.Ready, all.loadState)
        val scoped = all.forHostFilter("b")
        assertEquals(mapOf("b" to pending), scoped.hosts)
        assertEquals(TaskListLoadState.Loading, scoped.loadState)
        assertEquals(TaskListEmptyState.Loading, scoped.emptyState)
    }

    @Test fun offline_host_needs_recovery_when_another_host_has_sessions() {
        val offline = host("b", "B").copy(
            connection = ConnectionState.Offline(),
            initialSync = InitialSyncState.Unavailable,
        )
        val all = stateWithPopulatedHost(offline)

        assertEquals(TaskListLoadState.Ready, all.loadState)
        val scoped = all.forHostFilter("b")
        assertEquals(mapOf("b" to offline), scoped.hosts)
        assertEquals(TaskListLoadState.Unavailable, scoped.loadState)
        assertEquals(TaskListEmptyState.Recovery, scoped.emptyState)
    }

    @Test fun synchronized_empty_host_is_ready_when_selected() {
        val ready = host("b", "B").copy(
            connection = ConnectionState.Online(1),
            initialSync = InitialSyncState.Ready,
        )
        val scoped = stateWithPopulatedHost(ready).forHostFilter("b")

        assertEquals(mapOf("b" to ready), scoped.hosts)
        assertEquals(TaskListLoadState.Ready, scoped.loadState)
        assertEquals(TaskListEmptyState.Ready, scoped.emptyState)
        assertEquals(emptyList<Session>(), sessionsForHostFilter(scoped.hosts, null))
    }

    @Test fun all_hosts_keeps_existing_recovery_continuity_during_a_retry() {
        val retrying = SessionListState(
            hosts = mapOf("b" to host("b", "B")),
            loadState = TaskListLoadState.Loading,
            emptyState = TaskListEmptyState.Recovery,
        )

        assertSame(retrying, retrying.forHostFilter(null))
        assertEquals(TaskListEmptyState.Recovery, retrying.forHostFilter(null).emptyState)
        assertEquals(TaskListEmptyState.Loading, retrying.forHostFilter("b").emptyState)
    }

    private fun stateWithPopulatedHost(selected: HostState): SessionListState {
        val populated = host(
            "a", "A",
            sessions = listOf(session("visible", "a", active = false, created = "2026-01-01T00:00:00Z", updated = "2026-01-02T00:00:00Z")),
        )
        return sessionListState(AppState(hosts = mapOf("a" to populated, "b" to selected)))
    }

    private fun session(id: String, hostId: String, active: Boolean, created: String, updated: String) = Session(
        id, hostId, "/work", id, if (active) SessionStatus.Running else SessionStatus.Idle,
        "", false, null, created, updated, active,
    )

    private fun host(id: String, name: String, localName: String? = null, sessions: List<Session> = emptyList()) = HostState(
        PairedHost(Host(id, name, "", "", ""), "https://$id", "credential", "client", localName),
        sessions = sessions,
    )
}
