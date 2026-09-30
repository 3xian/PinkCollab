package dev.pinkcollab.ui

import dev.pinkcollab.data.AppState
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.Host
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.PairedHost
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SessionStatus
import dev.pinkcollab.data.SessionDetail
import dev.pinkcollab.data.SessionKey
import dev.pinkcollab.data.TaskListLoadState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PresentationFlowsTest {
    @Test fun bursts_deliver_first_snapshot_then_latest_without_losing_the_final_update() = runTest {
        val source = MutableStateFlow(0)
        val seen = mutableListOf<Int>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.batchLatestPresentation().toList(seen)
        }
        assertEquals(listOf(0), seen)
        repeat(100) { source.value = it + 1 }
        advanceTimeBy(74)
        assertEquals(listOf(0), seen)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0, 100), seen)
        advanceTimeBy(500)
        source.value = 101
        runCurrent()
        assertEquals(listOf(0, 100, 101), seen)
        collector.cancel()
        source.value = 102
        advanceTimeBy(500)
        assertEquals(listOf(0, 100, 101), seen)
    }

    @Test fun finite_source_flushes_its_last_snapshot_on_completion() = runTest {
        val seen = flow { repeat(100) { emit(it) } }.batchLatestPresentation().toList()
        assertEquals(99, seen.last())
        assertTrue(seen.size <= 2)
    }

    @Test fun navigation_ignores_socket_revisions_but_keeps_connection_and_loading_changes() {
        val host = HostState(PairedHost(Host("host", "Desktop", "", "", ""), "https://host", "secret", "client"))
        val initial = AppState(hosts = mapOf("host" to host))
        val revision = initial.copy(hosts = mapOf("host" to host.copy(revision = 100)))
        assertEquals(navigationState(initial), navigationState(revision))
        assertNotEquals(navigationState(initial), navigationState(initial.copy(loadingCredentials = true)))
        val offline = initial.copy(hosts = mapOf("host" to host.copy(connection = ConnectionState.Offline())))
        assertNotEquals(navigationState(initial), navigationState(offline))
    }

    @Test fun returning_to_tasks_observes_sessions_created_while_there_were_no_collectors() = runTest {
        val host = HostState(PairedHost(Host("host", "Desktop", "", "", ""), "", "", ""))
        val source = MutableStateFlow(AppState(hosts = mapOf("host" to host)))
        val presentation = source.sessionListPresentation(backgroundScope)
        val collector = backgroundScope.launch { presentation.collect {} }
        presentation.first { it.hosts.isNotEmpty() }
        collector.cancel()
        runCurrent()
        val created = Session("new", "host", "/work", "New session", SessionStatus.Idle,
            "", false, null, "", "", false)
        source.value = source.value.copy(hosts = mapOf("host" to host.copy(sessions = listOf(created))))
        withTimeout(5_000) {
            while (presentation.value.hosts.getValue("host").sessions.isEmpty()) yield()
        }
        assertEquals(listOf(created), presentation.value.hosts.getValue("host").sessions)
        assertEquals(listOf(created), presentation.first().hosts.getValue("host").sessions)
    }

    @Test fun task_list_ignores_transcripts_and_revisions_but_preserves_navigation_inputs() {
        val session = Session("session", "host", "/work", "Work", SessionStatus.Idle,
            "", false, null, "", "", false)
        val host = HostState(PairedHost(Host("host", "Desktop", "", "", ""), "", "", ""),
            sessions = listOf(session))
        val initial = AppState(hosts = mapOf("host" to host))
        val transcript = initial.copy(hosts = mapOf("host" to host.copy(revision = 123)),
            details = mapOf(SessionKey("host", "session") to SessionDetail(session)))
        assertTrue(sessionListState(initial).samePresentation(sessionListState(transcript)))
        assertEquals(navigationState(initial), navigationState(transcript))
        for (changed in listOf(
            initial.copy(loadingCredentials = true),
            initial.copy(hosts = emptyMap()),
            initial.copy(hosts = mapOf("host" to host.copy(connection = ConnectionState.Offline()))),
            initial.copy(hosts = mapOf("host" to host.copy(snapshotToken = "reconnected"))),
            initial.copy(hosts = mapOf("host" to host.copy(sessions = listOf(session.copy(title = "Edited"))))),
        )) assertFalse(sessionListState(initial).samePresentation(sessionListState(changed)))
        assertEquals(TaskListLoadState.Ready, sessionListState(initial).loadState)
    }
}
