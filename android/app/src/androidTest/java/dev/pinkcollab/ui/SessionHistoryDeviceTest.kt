package dev.pinkcollab.ui

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.Host
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.PairedHost
import dev.pinkcollab.data.RuntimeExecution
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
        null, "", "", true, "generation", RuntimeExecution.Quiescent)
    private val saved = TimelineItem("saved", "user", "Earlier question", "", "2026-09-20T00:00:00Z")
    private val live = TimelineItem("live", "user", "Current question", "", "2026-09-20T00:00:01Z")

    @Test fun saved_messages_and_live_updates_share_one_scrollable_timeline() {
        val detail = SessionDetail(session, nextHistoryCursor = "older",
            historyItems = listOf(saved), liveItems = listOf(live))
        compose.setContent {
            SessionPage(SessionPageState(LoadState.Ready(detail), host, SessionDraft(), 0,
                SessionActivity(), null, null), onAction = {})
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

    @Test fun detached_session_displays_saved_transcript() {
        val detail = SessionDetail(session.copy(status = SessionStatus.Idle, runtimeAttached = false,
            runtimeGeneration = null), historyItems = listOf(saved))
        compose.setContent {
            SessionPage(SessionPageState(LoadState.Ready(detail), host, SessionDraft(), 0,
                SessionActivity(), null, null), onAction = {})
        }

        compose.onNodeWithText("Earlier question").assertExists()
    }
}
