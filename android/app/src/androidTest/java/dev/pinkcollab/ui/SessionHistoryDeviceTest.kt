package dev.pinkcollab.ui

import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.background
import dev.pinkcollab.ui.theme.PinkCollabTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeFalse
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.Host
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.PairedHost
import dev.pinkcollab.data.SavedHistory
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SessionDetail
import dev.pinkcollab.data.SessionStatus
import dev.pinkcollab.data.TimelineItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionHistoryDeviceTest {
    @get:Rule val compose = createComposeRule()

    private val host = HostState(
        PairedHost(Host("host", "Desktop", "", "", ""), "https://host", "credential", "client"),
        connection = ConnectionState.Online(1L),
    )
    private val session = Session("session", "host", "/work", "Work", SessionStatus.Running, "", false,
        null, "", "", true, "generation")
    private val saved = TimelineItem("saved", "user", "Earlier question", "", "2026-09-20T00:00:00Z")
    private val live = TimelineItem("live", "user", "Current question", "", "2026-09-20T00:00:01Z")

    @Test fun retained_messages_remain_readable_while_subscription_refreshes() {
        val detail = SessionDetail(session, snapshotToken = null, savedHistory = SavedHistory.Loading)
        compose.setContent {
            SessionPage(SessionPageState(LoadState.Ready(detail), host, SessionDraft(), 0,
                SessionActivity(), null, null, historyItems = listOf(saved)),
                onAction = {}, onApplyModelSettings = { true })
        }
        compose.onNodeWithText("Earlier question").assertIsDisplayed()
        compose.onNodeWithTag("historyLoading").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        compose.onNodeWithTag("sessionInput").assertIsNotEnabled()
    }

    @Test fun subscription_refresh_does_not_report_an_online_host_as_offline() {
        val detail = mutableStateOf(SessionDetail(session, snapshotToken = "first", liveItems = listOf(live)))
        val connection = mutableStateOf(host)
        compose.setContent {
            SessionPage(SessionPageState(LoadState.Ready(detail.value), connection.value, SessionDraft(), 0,
                SessionActivity(), null, null), onAction = {}, onApplyModelSettings = { true })
        }
        compose.onNodeWithTag("sessionInput").assertIsEnabled()
        compose.runOnIdle { detail.value = detail.value.copy(snapshotToken = null) }
        compose.onNodeWithText("Host offline").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        compose.onNodeWithTag("sessionInput").assertIsNotEnabled()
        compose.runOnIdle { connection.value = host.copy(connection = ConnectionState.Offline()) }
        compose.onNodeWithText("Host offline").assertIsDisplayed()
        compose.runOnIdle { connection.value = host }
        compose.onNodeWithText("Host offline").assertDoesNotExist()
        compose.onNodeWithTag("sessionInput").assertIsNotEnabled()
        compose.runOnIdle { detail.value = detail.value.copy(snapshotToken = "second") }
        compose.onNodeWithTag("sessionInput").assertIsEnabled()
    }

    @Test fun composer_and_draft_survive_detail_loading_failure_and_retry() {
        val load = mutableStateOf<LoadState<SessionDetail>>(LoadState.Loading)
        compose.setContent {
            SessionPage(SessionPageState(load.value, host, SessionDraft(text = "Unsent draft"), 0,
                SessionActivity(), null, null, summary = session),
                onAction = {}, onApplyModelSettings = { true })
        }
        val input = compose.onNodeWithTag("sessionInput")
        input.assertIsDisplayed().assertIsNotEnabled().assertTextEquals("Unsent draft")
        compose.onNodeWithTag("sessionLoading").assertIsDisplayed()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertCountEquals(0)
        compose.runOnIdle { load.value = LoadState.Failed("Unavailable") }
        input.assertIsDisplayed().assertIsNotEnabled().assertTextEquals("Unsent draft")
        compose.onNodeWithText("Could not load this session").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.runOnIdle { load.value = LoadState.Loading }
        input.assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { load.value = LoadState.Ready(SessionDetail(session, snapshotToken = "sub", liveItems = listOf(live))) }
        input.assertIsDisplayed().assertIsEnabled().assertTextEquals("Unsent draft")
        compose.onNodeWithText("Current question").assertIsDisplayed()
        compose.onAllNodesWithTag("sessionWorkStatus").assertCountEquals(1)
    }

    @Test fun composer_remains_above_keyboard_and_returns_after_hide() {
        // The software keyboard on the headless CI emulator kills the emulator
        // partway through this check, which aborts the whole run. The keyboard
        // path is covered on a real device instead.
        val hardware = Build.HARDWARE.lowercase()
        assumeFalse(
            "Emulator software keyboards are not stable enough for this check",
            hardware.contains("ranchu") || hardware.contains("goldfish"),
        )
        lateinit var view: android.view.View
        compose.setContent {
            view = LocalView.current
            Box(Modifier.fillMaxSize().navigationBarsPadding()) {
                SessionPage(SessionPageState(LoadState.Ready(SessionDetail(session, snapshotToken = "sub", liveItems = listOf(live))),
                    host, SessionDraft(), 0, SessionActivity(), null, null),
                    onAction = {}, onApplyModelSettings = { true })
            }
        }
        val input = compose.onNode(hasSetTextAction())
        val originalBottom = input.fetchSemanticsNode().boundsInRoot.bottom
        input.performClick()
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.waitForIdle()
        input.assertIsDisplayed()
        val openBottom = input.fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Composer must move above the keyboard", openBottom < originalBottom - 100f)
        compose.runOnIdle {
            ViewCompat.getWindowInsetsController(view)?.hide(WindowInsetsCompat.Type.ime())
        }
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == false
        }
        compose.waitUntil(5_000) {
            kotlin.math.abs(input.fetchSemanticsNode().boundsInRoot.bottom - originalBottom) < 2f
        }
        compose.onNodeWithText("Current question").assertIsDisplayed()
    }

    @Test fun returning_to_preloaded_session_scrolls_to_latest_message() {
        val active = mutableStateOf(true)
        val messages = (0..40).map { live.copy(id = "message-$it", text = "Question $it") }
        val detail = SessionDetail(session, snapshotToken = "sub", liveItems = messages)
        compose.setContent {
            SessionPage(SessionPageState(LoadState.Ready(detail), host, SessionDraft(), 0,
                SessionActivity(), null, null), onAction = {}, onApplyModelSettings = { true },
                isActive = active.value)
        }
        compose.onNodeWithText("Question 40").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Question 0"))
        compose.onNodeWithText("Question 0").assertIsDisplayed()
        compose.runOnIdle { active.value = false }
        compose.waitForIdle()
        compose.runOnIdle { active.value = true }
        compose.onNodeWithText("Question 40").assertIsDisplayed()
    }

    @Test fun saved_messages_and_live_updates_share_one_scrollable_timeline() {
        val detail = SessionDetail(session, snapshotToken = "sub", savedHistory = SavedHistory.Ready(null, listOf(saved), "older"),
            liveItems = listOf(live))
        compose.setContent {
            SessionPage(SessionPageState(LoadState.Ready(detail), host, SessionDraft(), 0,
                SessionActivity(), null, null), onAction = {}, onApplyModelSettings = { true })
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Earlier question"))
        compose.onNodeWithText("Earlier question").assertExists()
        compose.onNodeWithText("Live updates", substring = true).assertDoesNotExist()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Current question"))
        compose.onNodeWithText("Current question").assertExists()
        compose.onNodeWithText("Show saved messages").assertDoesNotExist()
    }

    @Test fun pulling_down_loads_earlier_history_once_and_preserves_reading_position() {
        val messages = (0..30).map { saved.copy(id = "saved-$it", text = "Saved question $it") }
        val detail = mutableStateOf(SessionDetail(session, snapshotToken = "sub",
            savedHistory = SavedHistory.Ready(null, messages, "older")))
        val activity = mutableStateOf(SessionActivity())
        var requests = 0
        compose.setContent {
            SessionPage(SessionPageState(LoadState.Ready(detail.value), host, SessionDraft(), 0,
                activity.value, null, null), onAction = {
                if (it == SessionAction.LoadEarlierHistory) {
                    requests++
                    activity.value = SessionActivity(history = true)
                }
            }, onApplyModelSettings = { true })
        }
        val timeline = compose.onNodeWithTag("sessionTimeline")
        timeline.performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(0, requests) }
        timeline.performScrollToNode(hasText("Saved question 0"))
        timeline.performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithTag("historyPullIndicator").assertIsDisplayed()
        timeline.performTouchInput { swipeDown() }
        compose.runOnIdle {
            assertEquals(1, requests)
            val earlier = (0..20).map { saved.copy(id = "older-$it", text = "Older question $it") }
            detail.value = detail.value.copy(savedHistory = SavedHistory.Ready(null, earlier + messages, null))
            activity.value = SessionActivity()
        }
        compose.onNodeWithText("Saved question 0").assertIsDisplayed()
        compose.onNodeWithTag("historyPullIndicator").assertDoesNotExist()
        timeline.performScrollToNode(hasText("Older question 0"))
        timeline.performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun accessible_history_action_preserves_position_and_tracks_availability() {
        val messages = (0..30).map { saved.copy(id = "saved-$it", text = "Saved question $it") }
        val detail = mutableStateOf(SessionDetail(session, snapshotToken = "sub",
            savedHistory = SavedHistory.Ready(null, messages, "older")))
        val activity = mutableStateOf(SessionActivity())
        val connection = mutableStateOf<ConnectionState>(ConnectionState.Online(1L))
        val active = mutableStateOf(true)
        var requests = 0
        compose.setContent {
            SessionPage(SessionPageState(LoadState.Ready(detail.value),
                host.copy(connection = connection.value), SessionDraft(), 0, activity.value, null, null),
                onAction = {
                    if (it == SessionAction.LoadEarlierHistory) {
                        requests++
                        activity.value = SessionActivity(history = true)
                    }
                }, onApplyModelSettings = { true }, isActive = active.value)
        }
        val timeline = compose.onNodeWithTag("sessionTimeline")
        timeline.performScrollToNode(hasText("Saved question 0"))
        val loadEarlier = timeline.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == "Load earlier messages" }
        compose.runOnIdle { assertTrue(loadEarlier.action()) }
        compose.runOnIdle { assertEquals(1, requests) }
        assertTrue(timeline.fetchSemanticsNode().config
            .getOrElse(SemanticsActions.CustomActions) { emptyList() }.isEmpty())
        timeline.performTouchInput { swipeDown() }
        compose.runOnIdle {
            assertEquals(1, requests)
            val earlier = (0..20).map { saved.copy(id = "older-$it", text = "Older question $it") }
            detail.value = detail.value.copy(savedHistory = SavedHistory.Ready(null, earlier + messages, "more"))
            activity.value = SessionActivity()
        }
        compose.onNodeWithText("Saved question 0").assertIsDisplayed()
        for (unavailable in listOf("disconnected", "inactive", "exhausted")) {
            compose.runOnIdle {
                connection.value = if (unavailable == "disconnected") ConnectionState.Offline()
                    else ConnectionState.Online(2L)
                active.value = unavailable != "inactive"
                if (unavailable == "exhausted") {
                    detail.value = detail.value.copy(savedHistory = SavedHistory.Ready(null, messages, null))
                }
            }
            assertTrue(timeline.fetchSemanticsNode().config
                .getOrElse(SemanticsActions.CustomActions) { emptyList() }.isEmpty())
        }
    }

    @Test fun history_pull_is_disabled_without_more_messages_or_a_connected_active_session() {
        val detail = mutableStateOf(SessionDetail(session, snapshotToken = "sub",
            savedHistory = SavedHistory.Ready(null, listOf(saved), null)))
        val connection = mutableStateOf<ConnectionState>(ConnectionState.Online(1L))
        val active = mutableStateOf(true)
        var requests = 0
        compose.setContent {
            SessionPage(SessionPageState(LoadState.Ready(detail.value), host.copy(connection = connection.value),
                SessionDraft(), 0, SessionActivity(), null, null),
                onAction = { if (it == SessionAction.LoadEarlierHistory) requests++ },
                onApplyModelSettings = { true }, isActive = active.value)
        }
        val timeline = compose.onNodeWithTag("sessionTimeline")
        timeline.performTouchInput { swipeDown() }
        compose.runOnIdle {
            assertEquals(0, requests)
            detail.value = detail.value.copy(savedHistory = SavedHistory.Ready(null, listOf(saved), "older"))
            connection.value = ConnectionState.Offline()
        }
        timeline.performTouchInput { swipeDown() }
        compose.runOnIdle {
            assertEquals(0, requests)
            connection.value = ConnectionState.Online(2L)
            active.value = false
        }
        timeline.performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(0, requests) }
    }

    @Test fun history_in_flight_does_not_claim_there_are_no_saved_messages() {
        val detail = SessionDetail(session.copy(status = SessionStatus.Idle, runtimeAttached = false,
            generation = null), savedHistory = SavedHistory.Loading)
        compose.setContent {
            SessionPage(SessionPageState(LoadState.Ready(detail), host, SessionDraft(), 0,
                SessionActivity(), null, null), onAction = {}, onApplyModelSettings = { true })
        }

        compose.onNodeWithText("No saved messages yet").assertDoesNotExist()
        compose.onNodeWithTag("historyLoading").assertIsDisplayed()
    }

    @Test fun failed_history_does_not_claim_there_are_no_saved_messages() {
        val detail = SessionDetail(session.copy(status = SessionStatus.Idle, runtimeAttached = false,
            generation = null), snapshotToken = "subscription", savedHistory = SavedHistory.Failed)
        compose.setContent {
            SessionPage(SessionPageState(LoadState.Ready(detail), host, SessionDraft(), 0,
                SessionActivity(), null, null), onAction = {}, onApplyModelSettings = { true })
        }

        compose.onNodeWithText("No saved messages yet").assertDoesNotExist()
        compose.onNodeWithText("Could not load message history").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test fun reconnecting_history_shows_loading_and_recovers_without_a_retry_error() {
        val detail = mutableStateOf(SessionDetail(session.copy(status = SessionStatus.Idle,
            runtimeAttached = false, generation = null), savedHistory = SavedHistory.Failed))
        val connection = mutableStateOf<ConnectionState>(ConnectionState.Connecting)
        compose.setContent {
            SessionPage(SessionPageState(LoadState.Ready(detail.value), host.copy(connection = connection.value),
                SessionDraft(), 0, SessionActivity(), null, null, refreshError = "Old snapshot timeout"),
                onAction = {}, onApplyModelSettings = { true })
        }
        for (state in listOf(ConnectionState.Connecting, ConnectionState.Synchronizing)) {
            compose.runOnIdle { connection.value = state }
            compose.onNodeWithTag("historyLoading").assertIsDisplayed()
            compose.onNodeWithText("Could not load message history").assertDoesNotExist()
            compose.onNodeWithText("Old snapshot timeout").assertDoesNotExist()
            compose.onNodeWithText("Retry").assertDoesNotExist()
        }
        compose.runOnIdle {
            connection.value = ConnectionState.Online(2L)
            detail.value = detail.value.copy(snapshotToken = "new", savedHistory = SavedHistory.Loading)
        }
        compose.onNodeWithTag("historyLoading").assertIsDisplayed()
        compose.onNodeWithText("Old snapshot timeout").assertDoesNotExist()
        compose.runOnIdle { detail.value = detail.value.copy(savedHistory = SavedHistory.Ready(null, listOf(saved), null)) }
        compose.onNodeWithText("Earlier question").assertIsDisplayed()
        compose.onNodeWithTag("historyLoading").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test fun history_loading_stays_visible_until_transcript_arrives() {
        val detail = mutableStateOf(SessionDetail(session, snapshotToken = "sub", savedHistory = SavedHistory.Loading))
        compose.setContent {
            SessionPage(SessionPageState(LoadState.Ready(detail.value), host, SessionDraft(), 0,
                SessionActivity(), null, null), onAction = {}, onApplyModelSettings = { true })
        }
        compose.onNodeWithTag("historyLoading").assertIsDisplayed()
        compose.runOnIdle {
            detail.value = detail.value.copy(savedHistory = SavedHistory.Ready(null, listOf(saved), null))
        }
        compose.onNodeWithTag("historyLoading").assertDoesNotExist()
        compose.onNodeWithText("Earlier question").assertIsDisplayed()
    }

    @Test fun detached_session_without_saved_history_has_no_empty_message() {
        val detail = SessionDetail(session.copy(status = SessionStatus.Idle, runtimeAttached = false,
            generation = null))
        compose.setContent {
            SessionPage(SessionPageState(LoadState.Ready(detail), host, SessionDraft(), 0,
                SessionActivity(), null, null), onAction = {}, onApplyModelSettings = { true })
        }

        compose.onNodeWithText("No saved messages yet").assertDoesNotExist()
    }

    @Test fun detached_session_displays_saved_transcript() {
        val detail = SessionDetail(session.copy(status = SessionStatus.Idle, runtimeAttached = false,
            generation = null), savedHistory = SavedHistory.Ready(null, listOf(saved), null))
        compose.setContent {
            SessionPage(SessionPageState(LoadState.Ready(detail), host, SessionDraft(), 0,
                SessionActivity(), null, null), onAction = {}, onApplyModelSettings = { true })
        }

        compose.onNodeWithText("Earlier question").assertExists()
    }
}
