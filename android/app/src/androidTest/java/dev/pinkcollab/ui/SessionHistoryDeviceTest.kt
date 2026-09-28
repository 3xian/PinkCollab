package dev.pinkcollab.ui

import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertTrue
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
        compose.runOnIdle { load.value = LoadState.Failed("Unavailable") }
        input.assertIsDisplayed().assertIsNotEnabled().assertTextEquals("Unsent draft")
        compose.onNodeWithText("Could not load this session").assertIsDisplayed()
        compose.runOnIdle { load.value = LoadState.Loading }
        input.assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { load.value = LoadState.Ready(SessionDetail(session, liveItems = listOf(live))) }
        input.assertIsDisplayed().assertIsEnabled().assertTextEquals("Unsent draft")
        compose.onNodeWithText("Current question").assertIsDisplayed()
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
                SessionPage(SessionPageState(LoadState.Ready(SessionDetail(session, liveItems = listOf(live))),
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
        val detail = SessionDetail(session, liveItems = messages)
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
        val detail = SessionDetail(session, savedHistory = SavedHistory.Ready(null, listOf(saved), "older"),
            liveItems = listOf(live))
        compose.setContent {
            SessionPage(SessionPageState(LoadState.Ready(detail), host, SessionDraft(), 0,
                SessionActivity(), null, null), onAction = {}, onApplyModelSettings = { true })
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Load earlier messages"))
        compose.onNodeWithText("Load earlier messages").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Earlier question"))
        compose.onNodeWithText("Earlier question").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Live updates", substring = true))
        compose.onNodeWithText("Live updates", substring = true).assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Current question"))
        compose.onNodeWithText("Current question").assertExists()
        compose.onNodeWithText("Show saved messages").assertDoesNotExist()
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
            generation = null), savedHistory = SavedHistory.Failed)
        compose.setContent {
            SessionPage(SessionPageState(LoadState.Ready(detail), host, SessionDraft(), 0,
                SessionActivity(), null, null), onAction = {}, onApplyModelSettings = { true })
        }

        compose.onNodeWithText("No saved messages yet").assertDoesNotExist()
        compose.onNodeWithText("Could not load message history").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test fun history_loading_stays_visible_until_transcript_arrives() {
        val detail = mutableStateOf(SessionDetail(session, savedHistory = SavedHistory.Loading))
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
