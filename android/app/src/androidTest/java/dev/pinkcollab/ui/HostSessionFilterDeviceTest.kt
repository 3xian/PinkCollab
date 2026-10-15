package dev.pinkcollab.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pinkcollab.data.AppState
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.InitialSyncState
import dev.pinkcollab.data.Host
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.PairedHost
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SessionKey
import dev.pinkcollab.data.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostSessionFilterDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun host_filter_touch_target_aligns_with_the_first_session_card() {
        val session = session("office-session", "office", "2026-09-20T00:00:00Z")
        val hosts = mapOf("office" to host("office", "Office", listOf(session)))
        compose.setContent {
            TasksScreen(
                TasksScreenState(
                    sessionListState(AppState(hosts = hosts)), emptyMap(), emptyMap(), emptyMap(),
                    emptySet(), emptyMap(), emptyMap(), emptyMap(), SessionKey("office", session.id),
                ),
                TasksScreenActions({}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> true }),
            )
        }
        val filter = compose.onNodeWithTag("hostFilter")
        val card = compose.onNodeWithTag("sessionCard:${session.id}")
        assertEquals(
            card.fetchSemanticsNode().boundsInRoot.left,
            filter.fetchSemanticsNode().boundsInRoot.left,
            0.5f,
        )
        filter.performTouchInput { click(Offset(1f, center.y)) }
        compose.onNodeWithTag("hostFilterOption:all").assertIsDisplayed().assertIsSelected()
    }

    @Test fun host_filter_defaults_to_all_hosts_and_limits_the_session_strip() {
        val office = session("office-session", "office", "2026-09-20T00:00:00Z")
        val desk = session("desk-session", "desk", "2026-09-21T00:00:00Z")
        val hosts = mapOf(
            "office" to host("office", "Office", listOf(office)),
            "desk" to host("desk", "Desk", listOf(desk)),
        )
        var selected by mutableStateOf<SessionKey?>(SessionKey("office", "office-session"))
        compose.setContent {
            TasksScreen(
                TasksScreenState(
                    sessionListState(AppState(hosts = hosts)), emptyMap(), emptyMap(), emptyMap(),
                    emptySet(), emptyMap(), emptyMap(), emptyMap(), selected,
                ),
                TasksScreenActions({ selected = it }, {}, {}, {}, {}, { _, _ -> }, { _, _ -> true }),
            )
        }
        compose.onNodeWithTag("hostFilter").assertIsDisplayed()
        compose.onNodeWithText("All hosts").assertIsDisplayed()
        compose.onNodeWithTag("sessionCards").performScrollToNode(hasTestTag("sessionCard:office-session"))
        compose.onNodeWithTag("sessionCard:office-session").assertIsDisplayed()
        compose.onNodeWithTag("sessionCards").performScrollToNode(hasTestTag("sessionCard:desk-session"))
        compose.onNodeWithTag("sessionCard:desk-session").assertIsDisplayed()
        compose.onNodeWithTag("hostFilter").performClick()
        compose.onNodeWithTag("hostFilterOption:all").assertIsSelected()
        compose.onNodeWithTag("hostFilterOption:desk").assertIsDisplayed().performClick()

        compose.waitForIdle()
        assertEquals(SessionKey("desk", "desk-session"), selected)
        compose.onNodeWithText("Desk").assertIsDisplayed()
        compose.onNodeWithTag("sessionCard:desk-session").assertIsDisplayed()
        compose.onAllNodesWithTag("sessionCard:office-session").assertCountEquals(0)

        compose.runOnIdle { selected = SessionKey("office", "office-session") }
        compose.waitForIdle()
        assertEquals(SessionKey("office", "office-session"), selected)
        compose.onNodeWithText("All hosts").assertIsDisplayed()
        compose.onNodeWithTag("sessionCards").performScrollToNode(hasTestTag("sessionCard:office-session"))
        compose.onNodeWithTag("sessionCard:office-session").assertIsDisplayed()
        compose.onNodeWithTag("sessionCards").performScrollToNode(hasTestTag("sessionCard:desk-session"))
        compose.onNodeWithTag("sessionCard:desk-session").assertIsDisplayed()
    }

    @Test fun restored_empty_host_filter_can_select_sessions_when_they_arrive() {
        val office = session("office-session", "office", "2026-09-20T00:00:00Z")
        val first = session("desk-first", "desk", "2026-09-22T00:00:00Z")
        val second = session("desk-second", "desk", "2026-09-21T00:00:00Z")
        var hosts by mutableStateOf(mapOf(
            "office" to host("office", "Office", listOf(office)),
            "desk" to host("desk", "Desk", emptyList()),
        ))
        var selected by mutableStateOf<SessionKey?>(SessionKey("office", office.id))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            TasksScreen(
                TasksScreenState(
                    sessionListState(AppState(hosts = hosts)), emptyMap(), emptyMap(), emptyMap(),
                    emptySet(), emptyMap(), emptyMap(), emptyMap(), selected,
                ),
                TasksScreenActions({ selected = it }, {}, {}, {}, {}, { _, _ -> }, { _, _ -> true }),
            )
        }
        compose.onNodeWithTag("hostFilter").performClick()
        compose.onNodeWithTag("hostFilterOption:desk").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { hosts = hosts + ("desk" to host("desk", "Desk", listOf(first, second))) }
        compose.waitForIdle()
        assertEquals(SessionKey("desk", first.id), selected)
        compose.onNodeWithTag("sessionCards").performScrollToNode(hasTestTag("sessionCard:${second.id}"))
        compose.onNodeWithTag("sessionCard:${second.id}").performClick()
        compose.waitForIdle()
        assertEquals(SessionKey("desk", second.id), selected)
    }

    @Test fun equal_size_host_switch_pages_only_the_visible_host() {
        val hosts = listOf("office", "desk").associateWith { id ->
            host(id, id, (1..8).map { session("$id-$it", id, "2026-09-20T00:00:00Z") })
                .copy(connection = ConnectionState.Online(0), initialSync = InitialSyncState.Ready,
                    nextSessionsCursor = "earlier-$id")
        }
        var selected by mutableStateOf<SessionKey?>(SessionKey("office", "office-1"))
        val pagedHosts = mutableListOf<String>()
        compose.setContent {
            TasksScreen(
                TasksScreenState(
                    sessionListState(AppState(hosts = hosts)), emptyMap(), emptyMap(), emptyMap(),
                    emptySet(), emptyMap(), emptyMap(), emptyMap(), selected,
                ),
                TasksScreenActions({ selected = it }, {}, {}, {}, {}, { _, _ -> }, { _, _ -> true },
                    loadMoreSessions = { pagedHosts += it }),
            )
        }
        for (id in listOf("desk", "office")) {
            compose.onNodeWithTag("hostFilter").performClick()
            compose.onNodeWithTag("hostFilterOption:$id").performClick()
            compose.waitForIdle()
        }
        compose.runOnIdle { pagedHosts.clear() }
        compose.onNodeWithTag("sessionCards").performScrollToNode(hasTestTag("sessionCard:office-8"))
        compose.waitForIdle()
        assertEquals(setOf("office"), pagedHosts.toSet())
    }

    @Test fun offline_host_filter_uses_one_recovery_explanation() {
        val listed = session("listed", "offline", "2026-09-20T00:00:00Z")
        val onlineSession = session("online-session", "online", "2026-09-21T00:00:00Z")
        val hosts = mapOf(
            "online" to host("online", "Online", listOf(onlineSession)).copy(
                connection = ConnectionState.Online(1), initialSync = InitialSyncState.Ready,
            ),
            "offline" to host("offline", "Offline", listOf(listed)).copy(
                connection = ConnectionState.Offline(), initialSync = InitialSyncState.Ready,
            ),
            "empty" to host("empty", "Empty", emptyList()).copy(
                connection = ConnectionState.Offline(), initialSync = InitialSyncState.Unavailable,
            ),
        )
        compose.setContent {
            TasksScreen(
                TasksScreenState(
                    sessionListState(AppState(hosts = hosts)), emptyMap(), emptyMap(), emptyMap(),
                    emptySet(), emptyMap(), emptyMap(), emptyMap(), SessionKey("online", onlineSession.id),
                ),
                TasksScreenActions({}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> true }),
            )
        }
        compose.onNodeWithTag("hostFilter").performClick()
        compose.onNodeWithTag("hostFilterOption:offline").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Can’t reach this host").assertIsDisplayed()
        compose.onNodeWithText("Could not load this session").assertDoesNotExist()
        compose.onNodeWithTag("sessionCard:listed").assertIsDisplayed()

        compose.onNodeWithTag("hostFilter").performClick()
        compose.onNodeWithTag("hostFilterOption:empty").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Can’t reach this host").assertIsDisplayed()
        compose.onNodeWithText("Could not load this session").assertDoesNotExist()
    }

    @Test fun expanded_filter_updates_host_status_without_changing_selection() {
        var connection by mutableStateOf<ConnectionState>(ConnectionState.Online(1))
        val listed = session("session", "office", "2026-09-20T00:00:00Z")
        compose.setContent {
            val hosts = mapOf("office" to host("office", "Office", listOf(listed)).copy(
                connection = connection, initialSync = InitialSyncState.Ready,
            ))
            TasksScreen(
                TasksScreenState(
                    sessionListState(AppState(hosts = hosts)), emptyMap(), emptyMap(), emptyMap(),
                    emptySet(), emptyMap(), emptyMap(), emptyMap(), SessionKey("office", listed.id),
                ),
                TasksScreenActions({}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> true }),
            )
        }
        compose.onNodeWithTag("hostFilter").performClick()
        val option = compose.onNodeWithTag("hostFilterOption:office")
        for ((state, label) in listOf(
            ConnectionState.Online(1) to "Connected",
            ConnectionState.Offline() to "Offline",
            ConnectionState.Connecting to "Connecting",
            ConnectionState.Synchronizing to "Syncing",
            ConnectionState.AuthenticationRequired to "Reconnect required",
            ConnectionState.UpgradeRequired to "Update required",
        )) {
            compose.runOnIdle { connection = state }
            option.assertIsDisplayed()
            compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("hostFilterOption:office")),
                useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("hostFilterOption:all").assertIsSelected()
        }
    }

    private fun session(id: String, hostId: String, updated: String) = Session(
        id, hostId, "/work", id, SessionStatus.Idle, "", false, null, updated, updated, false,
    )

    private fun host(id: String, name: String, sessions: List<Session>) = HostState(
        PairedHost(Host(id, name, "", "", ""), "https://$id", "credential", "client"),
        sessions = sessions,
    )
}
