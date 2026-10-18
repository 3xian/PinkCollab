package dev.pinkcollab.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pinkcollab.data.AppState
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.Host
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.PairedHost
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SessionKey
import dev.pinkcollab.data.SessionStatus
import dev.pinkcollab.data.SessionDetail
import dev.pinkcollab.data.SavedHistory
import dev.pinkcollab.data.TimelineItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class TasksPagerBarDeviceTest {
    @get:Rule val compose = createComposeRule()

    /** The composer placeholder repeats the latest user text, so timeline checks must not use it. */
    private fun timelineMessage(text: String) = compose.onNode(
        hasText(text) and hasAnyAncestor(hasTestTag("sessionTimeline")),
    )

    private val sessions = (0..5).map { index ->
        val timestamp = "2026-09-${(28 - index).toString().padStart(2, '0')}T00:00:00Z"
        Session("s$index", "host", "/work/project-$index/", "Session $index",
            if (index == 2) SessionStatus.NeedsInput else SessionStatus.Idle, "", false,
            null, timestamp, timestamp, false)
    }

    @Test fun selected_card_opens_session_info_without_reselecting_or_refreshing() {
        var selected by mutableStateOf<SessionKey?>(SessionKey("host", "s0"))
        var host by mutableStateOf(HostState(
            PairedHost(Host("host", "Desktop", "", "", ""), "", "", "", localName = "My desktop"),
            connection = ConnectionState.Online(1L), sessions = sessions.take(2),
        ))
        val selections = mutableListOf<SessionKey>()
        compose.setContent {
            TasksScreen(
                TasksScreenState(
                    sessionListState(AppState(hosts = mapOf("host" to host))),
                    emptyMap(), emptyMap(), emptyMap(), emptySet(), emptyMap(), emptyMap(), emptyMap(), selected,
                ),
                TasksScreenActions({ selected = it; selections += it }, {}, {}, {}, {}, { _, _ -> }, { _, _ -> true }),
            )
        }
        compose.onNodeWithTag("sessionCard:s1").performClick()
        compose.waitForIdle()
        assertEquals(SessionKey("host", "s1"), selected)
        compose.onNodeWithTag("sessionInfo").assertDoesNotExist()
        val selectionCount = selections.size

        compose.onNodeWithTag("sessionCard:s1").performClick()
        compose.onNodeWithTag("sessionInfo").assertIsDisplayed()
        compose.onNode(hasText("Connected") and hasAnyAncestor(hasTestTag("sessionInfoHost")))
            .assertIsDisplayed()
        for (value in listOf("Session 1", "My desktop", "/work/project-1/", "Inactive", "s1")) {
            compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag("sessionInfo")))
                .performScrollTo().assertIsDisplayed()
        }
        assertEquals(selectionCount, selections.size)
        // Move the selected inactive session from the second page to the first.
        compose.runOnIdle {
            host = host.copy(sessions = host.sessions.map {
                if (it.id == "s1") it.copy(updatedAt = "2026-09-30T00:00:00Z") else it
            })
        }
        compose.waitForIdle()
        assertEquals(SessionKey("host", "s1"), selected)
        compose.onNodeWithTag("sessionInfo").assertIsDisplayed()
        compose.onNode(hasText("s1") and hasAnyAncestor(hasTestTag("sessionInfo")))
            .performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            host = host.copy(
                sessions = host.sessions.map { if (it.id == "s1") it.copy(cwd = "/work/renamed-project/") else it },
                connection = ConnectionState.Offline(),
                paired = host.paired.copy(localName = "Renamed desktop"),
            )
        }
        compose.onNodeWithText("/work/renamed-project/").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Offline") and hasAnyAncestor(hasTestTag("sessionInfoHost")))
            .performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Renamed desktop") and hasAnyAncestor(hasTestTag("sessionInfo")))
            .performScrollTo().assertIsDisplayed()
        assertEquals(SessionKey("host", "s1"), selected)
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("sessionInfo").assertDoesNotExist()
        compose.onNodeWithTag("sessionCard:s1").performClick()
        compose.onNodeWithTag("sessionInfo").assertIsDisplayed()
        compose.runOnIdle { selected = SessionKey("host", "s0") }
        compose.waitForIdle()
        compose.onNodeWithTag("sessionInfo").assertDoesNotExist()
        compose.runOnIdle { selected = SessionKey("host", "s1") }
        compose.waitForIdle()
        compose.onNodeWithTag("sessionInfo").assertDoesNotExist()
        compose.onNodeWithTag("sessionCard:s1").performClick()
        compose.onNodeWithTag("sessionInfo").assertIsDisplayed()
        val removedSession = host.sessions.first { it.id == "s1" }
        compose.runOnIdle { host = host.copy(sessions = host.sessions.filterNot { it.id == "s1" }) }
        compose.waitForIdle()
        compose.onNodeWithTag("sessionInfo").assertDoesNotExist()
        compose.runOnIdle { host = host.copy(sessions = host.sessions + removedSession) }
        compose.waitForIdle()
        compose.onNodeWithTag("sessionInfo").assertDoesNotExist()
    }

    @Test fun active_sessions_precede_inactive_with_group_specific_timestamp_order() {
        val activeOlder = sessions[0].copy(
            id = "active-older", runtimeAttached = true,
            createdAt = "2026-09-20T00:00:00Z", updatedAt = "2026-09-29T00:00:00Z")
        val activeNewer = sessions[1].copy(
            id = "active-newer", runtimeAttached = true,
            createdAt = "2026-09-21T00:00:00Z", updatedAt = "2026-09-22T00:00:00Z")
        val inactiveUpdated = sessions[2].copy(
            id = "inactive-updated",
            createdAt = "2026-09-23T00:00:00Z", updatedAt = "2026-09-28T00:00:00Z")
        val inactiveCreated = sessions[3].copy(
            id = "inactive-created",
            createdAt = "2026-09-24T00:00:00Z", updatedAt = "2026-09-25T00:00:00Z")
        val host = HostState(
            PairedHost(Host("host", "Desktop", "", "", ""), "", "", ""),
            sessions = listOf(inactiveCreated, activeOlder, inactiveUpdated, activeNewer))
        var selected by mutableStateOf<SessionKey?>(null)
        compose.setContent {
            TasksScreen(
                TasksScreenState(sessionListState(AppState(hosts = mapOf("host" to host))), emptyMap(), emptyMap(), emptyMap(),
                    emptySet(), emptyMap(), emptyMap(), emptyMap(), selected),
                TasksScreenActions({ selected = it }, {}, {}, {}, {}, { _, _ -> }, { _, _ -> true }),
            )
        }
        compose.waitForIdle()
        assertEquals(SessionKey("host", "active-newer"), selected)
        for (id in listOf("active-older", "inactive-updated", "inactive-created")) {
            compose.onNodeWithTag("sessionTimelinePager").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            assertEquals(SessionKey("host", id), selected)
            compose.onNodeWithTag("sessionCard:$id").assertIsDisplayed()
        }
    }

    @Test fun retained_messages_survive_leaving_tasks_during_refresh() {
        val key = SessionKey("host", "s0")
        val host = HostState(PairedHost(Host("host", "Desktop", "", "", ""), "", "", ""),
            connection = ConnectionState.Online(1L), sessions = sessions.take(1))
        val detail = SessionDetail(sessions.first(), snapshotToken = "subscription",
            savedHistory = SavedHistory.Ready(null, listOf(TimelineItem("message", "user", "Retained conversation", "", "")), null))
        var app by mutableStateOf(AppState(hosts = mapOf("host" to host), details = mapOf(key to detail)))
        var showTasks by mutableStateOf(true)
        val cache = androidx.compose.runtime.mutableStateMapOf<SessionKey, SessionDisplay>()
        compose.setContent {
            if (showTasks) TasksScreen(
                TasksScreenState(sessionListState(app), app.details, emptyMap(), emptyMap(), emptySet(), emptyMap(),
                    emptyMap(), emptyMap(), key),
                TasksScreenActions({}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> true }), retainedDisplay = cache)
        }
        timelineMessage("Retained conversation").assertIsDisplayed()
        compose.runOnIdle { showTasks = false; app = app.copy(details = emptyMap()) }
        compose.onNodeWithText("Retained conversation").assertDoesNotExist()
        compose.runOnIdle { showTasks = true }
        timelineMessage("Retained conversation").assertIsDisplayed()
        compose.onNodeWithTag("historyLoading").assertDoesNotExist()
    }

    @Test fun cached_messages_remain_visible_during_detail_and_history_refresh() {
        val key = SessionKey("host", "s0")
        val host = HostState(PairedHost(Host("host", "Desktop", "", "", ""), "", "", ""),
            connection = ConnectionState.Online(1L), sessions = sessions)
        val message = TimelineItem("message", "user", "Keep this visible", "", "")
        val detail = SessionDetail(sessions.first(), snapshotToken = "subscription",
            savedHistory = SavedHistory.Ready(null, listOf(message), null))
        var app by mutableStateOf(AppState(hosts = mapOf("host" to host), details = mapOf(key to detail)))
        compose.setContent {
            TasksScreen(TasksScreenState(sessionListState(app), app.details, emptyMap(), emptyMap(), emptySet(), emptyMap(),
                emptyMap(), emptyMap(), key),
                TasksScreenActions({}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> true }))
        }
        timelineMessage("Keep this visible").assertIsDisplayed()
        compose.runOnIdle { app = app.copy(details = emptyMap()) }
        timelineMessage("Keep this visible").assertIsDisplayed()
        compose.runOnIdle { app = app.copy(details = mapOf(key to detail.copy(savedHistory = SavedHistory.Loading))) }
        timelineMessage("Keep this visible").assertIsDisplayed()
        compose.runOnIdle { app = app.copy(details = mapOf(key to detail.copy(savedHistory = SavedHistory.Failed))) }
        timelineMessage("Keep this visible").assertIsDisplayed()
        compose.onNodeWithText("Could not load message history").assertIsDisplayed()
    }

    @Test fun empty_cache_shows_loading_then_failure_without_false_empty_message() {
        val key = SessionKey("host", "s0")
        val host = HostState(PairedHost(Host("host", "Desktop", "", "", ""), "", "", ""),
            connection = ConnectionState.Online(1L), sessions = sessions)
        val detail = SessionDetail(sessions.first(), snapshotToken = "subscription",
            savedHistory = SavedHistory.Ready(null, emptyList(), null))
        var app by mutableStateOf(AppState(hosts = mapOf("host" to host), details = mapOf(key to detail)))
        compose.setContent {
            TasksScreen(TasksScreenState(sessionListState(app), app.details, emptyMap(), emptyMap(), emptySet(), emptyMap(),
                emptyMap(), emptyMap(), key),
                TasksScreenActions({}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> true }))
        }
        compose.onNodeWithText("No saved messages yet").assertDoesNotExist()
        compose.runOnIdle { app = app.copy(details = mapOf(key to detail.copy(savedHistory = SavedHistory.Loading))) }
        compose.onNodeWithTag("historyLoading").assertIsDisplayed()
        compose.onNodeWithText("No saved messages yet").assertDoesNotExist()
        compose.runOnIdle { app = app.copy(details = mapOf(key to detail.copy(savedHistory = SavedHistory.Failed))) }
        compose.onNodeWithTag("historyLoading").assertDoesNotExist()
        compose.onNodeWithText("Could not load message history").assertIsDisplayed()
        compose.onNodeWithText("No saved messages yet").assertDoesNotExist()
    }

    @Test fun cancelled_swipe_does_not_refresh_but_switch_and_reconnect_do() {
        var selected by mutableStateOf<SessionKey?>(SessionKey("host", "s0"))
        var host by mutableStateOf(HostState(
            PairedHost(Host("host", "Desktop", "", "", ""), "", "", ""), sessions = sessions))
        val requests = mutableListOf<String>()
        compose.setContent {
            TasksScreen(TasksScreenState(sessionListState(AppState(hosts = mapOf("host" to host))), emptyMap(), emptyMap(), emptyMap(),
                emptySet(), emptyMap(), emptyMap(), emptyMap(), selected),
                TasksScreenActions({ selected = it }, {}, {}, {}, {}, { _, _ -> }, { _, _ -> true }))
            LaunchedEffect(Unit) {
                snapshotFlow { sessionListState(AppState(hosts = mapOf("host" to host))) }
                    .followTaskFocus(snapshotFlow { selected }) { requests += it.id }
            }
        }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertEquals(listOf("s0"), requests) }
        compose.onNodeWithTag("sessionTimelinePager").performTouchInput {
            swipe(Offset(width * 0.5f, height * 0.5f), Offset(width * 0.45f, height * 0.5f), 600)
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(listOf("s0"), requests) }
        compose.onNodeWithTag("sessionTimelinePager").performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(listOf("s0", "s1"), requests) }
        compose.onNodeWithTag("sessionTimelinePager").performTouchInput { swipeRight() }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle {
            assertEquals(listOf("s0", "s1", "s0"), requests)
            host = host.copy(snapshotToken = "new-subscription")
        }
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertEquals(listOf("s0", "s1", "s0", "s0"), requests) }
    }

    @Test fun cards_scroll_freely_and_selection_tracks_timeline_swipes() {
        var selected by mutableStateOf<SessionKey?>(SessionKey("host", "s0"))
        val host = HostState(PairedHost(Host("host", "Desktop", "", "", ""), "", "", ""), sessions = sessions)
        compose.setContent {
            TasksScreen(
                TasksScreenState(sessionListState(AppState(hosts = mapOf("host" to host))), emptyMap(), emptyMap(), emptyMap(),
                    emptySet(), emptyMap(), emptyMap(), emptyMap(), selected),
                TasksScreenActions({ selected = it }, {}, {}, {}, {}, { _, _ -> }, { _, _ -> true }),
            )
        }
        compose.waitForIdle()
        val strip = compose.onNodeWithTag("sessionCards").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("sessionCard:s0").fetchSemanticsNode().boundsInRoot
        assertTrue("First card stays at the start", abs(first.left - strip.left - 12 * compose.density.density) <= 2f)

        // Scrolling the strip alone must not select a different timeline.
        compose.onNodeWithTag("sessionCards").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(SessionKey("host", "s0"), selected)
        compose.onNodeWithTag("sessionCards").performScrollToNode(hasTestTag("sessionCard:s5"))
        compose.onNodeWithTag("sessionCard:s5").performClick()
        compose.waitForIdle()
        assertEquals(SessionKey("host", "s5"), selected)
        assertCentered("s5")

        compose.onNodeWithTag("sessionCards").performScrollToNode(hasTestTag("sessionCard:s0"))
        compose.onNodeWithTag("sessionCard:s0").performClick()
        compose.waitForIdle()
        assertEquals(SessionKey("host", "s0"), selected)

        compose.onNodeWithTag("sessionTimelinePager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(SessionKey("host", "s1"), selected)
        assertCentered("s1")

        compose.onNodeWithTag("sessionCards").performScrollToNode(hasTestTag("sessionCard:s2"))
        compose.onNodeWithTag("sessionCard:s2").performClick()
        compose.waitForIdle()
        assertEquals(SessionKey("host", "s2"), selected)
        // Host connectivity takes precedence over cached session attention.
        assertEquals(SessionCardStatus.Connecting.label,
            compose.onNodeWithTag("sessionCard:s2").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        assertCentered("s2")

        compose.onNodeWithTag("sessionTimelinePager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(SessionKey("host", "s3"), selected)
        assertCentered("s3")
    }

    @Test fun pending_created_session_is_not_replaced_by_the_previous_page() {
        val created = sessions.first().copy(id = "new-session", title = "New session",
            updatedAt = "2026-09-30T00:00:00Z")
        val createdKey = SessionKey("host", created.id)
        var selected by mutableStateOf<SessionKey?>(createdKey)
        var host by mutableStateOf(HostState(
            PairedHost(Host("host", "Desktop", "", "", ""), "", "", ""), sessions = sessions))
        val selections = mutableListOf<SessionKey>()
        compose.setContent {
            TasksScreen(
                TasksScreenState(sessionListState(AppState(hosts = mapOf("host" to host))),
                    emptyMap(), emptyMap(), emptyMap(), emptySet(), emptyMap(), emptyMap(), emptyMap(), selected),
                TasksScreenActions({ selections += it; selected = it }, {}, {}, {}, {},
                    { _, _ -> }, { _, _ -> true }),
            )
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(createdKey, selected)
            assertTrue(selections.isEmpty())
            host = host.copy(sessions = host.sessions + created)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(createdKey, selected)
            assertTrue(selections.all { it == createdKey })
        }
        compose.onNodeWithTag("sessionCard:${created.id}").assertIsDisplayed()
        compose.onNodeWithTag("sessionTimelinePager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(SessionKey("host", "s0"), selected)
    }

    @Test fun removing_the_selected_session_allows_the_remaining_page_to_be_selected() {
        var selected by mutableStateOf<SessionKey?>(SessionKey("host", "s0"))
        var host by mutableStateOf(HostState(
            PairedHost(Host("host", "Desktop", "", "", ""), "", "", ""), sessions = sessions))
        compose.setContent {
            TasksScreen(
                TasksScreenState(sessionListState(AppState(hosts = mapOf("host" to host))),
                    emptyMap(), emptyMap(), emptyMap(), emptySet(), emptyMap(), emptyMap(), emptyMap(), selected),
                TasksScreenActions({ selected = it }, {}, {}, {}, {}, { _, _ -> }, { _, _ -> true }),
            )
        }
        compose.waitForIdle()
        compose.runOnIdle { host = host.copy(sessions = host.sessions.drop(1)) }
        compose.waitForIdle()
        assertEquals(SessionKey("host", "s1"), selected)
    }

    @Test fun model_picker_survives_runtime_start_and_failure_reordering() {
        val target = sessions.last()
        val targetKey = SessionKey("host", target.id)
        var selected by mutableStateOf<SessionKey?>(targetKey)
        var host by mutableStateOf(HostState(
            PairedHost(Host("host", "Desktop", "", "", ""), "", "", ""),
            connection = ConnectionState.Online(1L), sessions = sessions))
        var detail by mutableStateOf(SessionDetail(target, snapshotToken = "subscription"))
        var operations by mutableStateOf(emptySet<SessionOperationKey>())
        var runtimeStart by mutableStateOf<RuntimeStartAttempt?>(null)
        compose.setContent {
            TasksScreen(
                TasksScreenState(sessionListState(AppState(hosts = mapOf("host" to host))),
                    mapOf(targetKey to detail), emptyMap(), emptyMap(), operations,
                    emptyMap(), emptyMap(), emptyMap(), selected,
                    runtimeStarts = runtimeStart?.let { mapOf(targetKey to it) } ?: emptyMap()),
                TasksScreenActions({ selected = it }, {}, {}, {}, {}, { _, action ->
                    if (action == SessionAction.Command(SessionUserCommand.Start)) {
                        operations = setOf(SessionOperationKey(targetKey, SessionLane.Action))
                        runtimeStart = RuntimeStartAttempt.Pending(null)
                        val starting = target.copy(status = SessionStatus.Starting)
                        detail = detail.copy(session = starting)
                        host = host.copy(sessions = sessions.dropLast(1) + starting)
                    }
                }, { _, _ -> true }),
            )
        }
        compose.onNode(hasContentDescription("Choose model: model") and isEnabled()).performClick()
        compose.onNodeWithText("Starting OMP…").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(targetKey, selected)
            operations = emptySet()
            detail = detail.copy(session = target)
            runtimeStart = RuntimeStartAttempt.Failed("OMP did not load stored session")
            host = host.copy(sessions = sessions)
        }
        compose.onNodeWithText("Could not start OMP\nOMP did not load stored session").assertIsDisplayed()
        compose.runOnIdle { assertEquals(targetKey, selected) }
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Starting OMP…").assertIsDisplayed()
    }

    @Test fun long_status_stays_in_the_corner_without_covering_the_project() {
        val session = sessions.first().copy(cwd = "/work/project")
        val host = HostState(PairedHost(Host("host", "Desktop", "", "", ""), "", "", ""),
            connection = ConnectionState.UpgradeRequired, sessions = listOf(session))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                dev.pinkcollab.ui.theme.PinkCollabTheme {
                    TasksScreen(
                        TasksScreenState(sessionListState(AppState(hosts = mapOf("host" to host))),
                            emptyMap(), emptyMap(), emptyMap(), emptySet(), emptyMap(), emptyMap(), emptyMap(),
                            SessionKey("host", session.id)),
                        TasksScreenActions({}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> true }),
                    )
                }
            }
        }
        compose.waitForIdle()
        val cardMatcher = hasAnyAncestor(hasTestTag("sessionCard:${session.id}"))
        val cardBounds = compose.onNodeWithTag("sessionCard:${session.id}").fetchSemanticsNode().boundsInRoot
        val title = compose.onNode(hasText("Session 0") and cardMatcher, useUnmergedTree = true)
        val hostLabel = compose.onNode(hasText("Desktop") and cardMatcher, useUnmergedTree = true)
        val workspace = compose.onNode(hasText("project") and cardMatcher, useUnmergedTree = true)
        val status = compose.onNode(hasText(SessionCardStatus.UpdateRequired.label) and cardMatcher, useUnmergedTree = true)
        title.assertIsDisplayed()
        hostLabel.assertIsDisplayed()
        workspace.assertIsDisplayed()
        status.assertIsDisplayed()
        val titleBounds = title.fetchSemanticsNode().boundsInRoot
        val hostBounds = hostLabel.fetchSemanticsNode().boundsInRoot
        val workspaceBounds = workspace.fetchSemanticsNode().boundsInRoot
        val statusBounds = status.fetchSemanticsNode().boundsInRoot
        assertTrue(hostBounds.right <= workspaceBounds.left)
        assertTrue(titleBounds.width > 8f)
        assertTrue(titleBounds.right <= statusBounds.left + 1f)
        assertTrue(statusBounds.top <= titleBounds.top + 1f)
        assertTrue(statusBounds.bottom <= workspaceBounds.top + 1f)
        assertTrue(statusBounds.left > cardBounds.center.x)
        assertTrue(cardBounds.right - statusBounds.right <= 12f * compose.density.density)
    }

    private fun assertCentered(id: String) {
        val strip = compose.onNodeWithTag("sessionCards").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("sessionCard:$id").fetchSemanticsNode().boundsInRoot
        assertTrue("$id centered: ${card.center.x} vs ${strip.center.x}",
            abs(card.center.x - strip.center.x) <= 2f)
    }
}
