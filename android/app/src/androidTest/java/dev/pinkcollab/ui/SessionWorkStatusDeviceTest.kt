package dev.pinkcollab.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pinkcollab.data.*
import dev.pinkcollab.ui.theme.PinkCollabTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionWorkStatusDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun work_status_stays_one_line_above_composer_and_attention_remains_actionable() {
        val host = HostState(
            PairedHost(Host("host", "Desktop", "", "", ""), "https://host", "credential", "client"),
            connection = ConnectionState.Online(1L),
        )
        val session = Session("session", "host", "/work", "Work", SessionStatus.Running, "", false,
            null, "", "", true, "generation", workTiming = WorkTiming(23_000, false, false))
        val action = "Inspecting the composer clearance around a very long current tool action label"
        val target = "src/Session.kt"
        val tool = TimelineItem("read", "tool", "", "", "", ToolTrace("read", "read",
            ToolArguments(strings = mapOf("i" to action, "path" to target)), "", false, false))
        val detail = mutableStateOf(SessionDetail(session, snapshotToken = "subscription",
            savedHistory = SavedHistory.Ready("history", emptyList(), null), liveItems = listOf(tool)))
        val actions = mutableListOf<SessionAction>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PinkCollabTheme {
                SessionPage(SessionPageState(LoadState.Ready(detail.value), host, SessionDraft(), 0,
                    SessionActivity(), null, null), { actions += it }, { true })
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onAllNodesWithTag("sessionWorkStatus").assertCountEquals(1)
        val status = compose.onNodeWithTag("sessionWorkStatus")
        status.assertIsDisplayed()
        val statusBounds = status.fetchSemanticsNode().boundsInRoot
        val composerBounds = compose.onNodeWithTag("sessionComposer").fetchSemanticsNode().boundsInRoot
        assertTrue(statusBounds.bottom <= composerBounds.top)
        val title = compose.onNode(hasText(action) and hasAnyAncestor(hasTestTag("sessionWorkStatus")))
        val elapsed = compose.onNode(hasText("23s") and hasAnyAncestor(hasTestTag("sessionWorkStatus")))
        title.assertIsDisplayed()
        elapsed.assertIsDisplayed()
        assertEquals(title.fetchSemanticsNode().boundsInRoot.center.y,
            elapsed.fetchSemanticsNode().boundsInRoot.center.y, 1f)
        val layout = mutableListOf<TextLayoutResult>()
        title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        assertEquals(1, layout.single().lineCount)
        assertTrue(layout.single().isLineEllipsized(0))
        compose.onAllNodes(hasText(target) and hasAnyAncestor(hasTestTag("sessionWorkStatus")))
            .assertCountEquals(0)
        compose.onNodeWithContentDescription("Fast mode").assertIsEnabled()

        val attention = Attention("confirm", AttentionType.Confirm, "Apply the inspected changes?", emptyList())
        compose.runOnIdle {
            detail.value = detail.value.copy(session = session.copy(attention = attention, status = SessionStatus.NeedsInput))
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onAllNodesWithTag("sessionWorkStatus").assertCountEquals(1)
        val waitingStatusBounds = compose.onNodeWithTag("sessionWorkStatus").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val waitingComposerBounds = compose.onNodeWithTag("sessionComposer").fetchSemanticsNode().boundsInRoot
        assertTrue(waitingStatusBounds.bottom <= waitingComposerBounds.top)
        compose.onNodeWithText(attention.text).assertIsDisplayed()
            .assert(hasAnyAncestor(hasTestTag("sessionComposer")).not())
        compose.onNodeWithText("Confirm").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(SessionAction.Respond(AttentionResponse.Confirmation(attention.id, true)), actions.last())
        }
    }
}
