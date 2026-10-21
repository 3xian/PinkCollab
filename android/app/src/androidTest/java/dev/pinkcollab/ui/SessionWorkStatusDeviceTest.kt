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
            null, "", "", true, "generation", workTiming = WorkTiming(23_000, false, false), tokensPerSecond = 42.3)
        val action = "Inspecting the composer clearance around a very long current tool action label"
        val target = "src/Session.kt"
        val tool = TimelineItem("read", "tool", "", "", "", ToolTrace("read", "read",
            ToolArguments(strings = mapOf("i" to action, "path" to target)), "", false, false))
        val detail = mutableStateOf(SessionDetail(session, snapshotToken = "subscription",
            savedHistory = SavedHistory.Ready("history", emptyList(), null), liveItems = listOf(tool)))
        val actions = mutableListOf<SessionAction>()
        val currentHost = mutableStateOf(host)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PinkCollabTheme {
                SessionPage(SessionPageState(LoadState.Ready(detail.value), currentHost.value, SessionDraft(), 0,
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
        compose.onAllNodes(hasText(action) and hasAnyAncestor(hasTestTag("sessionWorkStatus")))
            .assertCountEquals(0)
        compose.onAllNodes(hasText(action) and hasAnyAncestor(hasTestTag("activityTimeline")))
            .onFirst().assertIsDisplayed()
        val elapsed = compose.onNode(hasText("23s") and hasAnyAncestor(hasTestTag("sessionWorkStatus")))
        elapsed.assertIsDisplayed()
        val speed = compose.onNodeWithTag("sessionWorkSpeed").assertTextEquals("42 tok/s").assertIsDisplayed()
        val speedBounds = speed.fetchSemanticsNode().boundsInRoot
        assertTrue(speedBounds.left >= elapsed.fetchSemanticsNode().boundsInRoot.right)
        assertTrue(speedBounds.right <= statusBounds.right)
        assertEquals(elapsed.fetchSemanticsNode().boundsInRoot.center.y, speedBounds.center.y, 1f)
        val speedLayout = mutableListOf<TextLayoutResult>()
        speed.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(speedLayout) }
        assertEquals(1, speedLayout.single().lineCount)
        assertTrue("Speed width ${speedLayout.single().size.width}, line ${speedLayout.single().getLineRight(0)}",
            speedLayout.single().getLineRight(0) <= speedLayout.single().size.width + 1f)
        val speedRight = speedBounds.right
        compose.runOnIdle {
            detail.value = detail.value.copy(session = detail.value.session.copy(tokensPerSecond = 58.7))
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("sessionWorkSpeed").assertTextEquals("59 tok/s")
        assertEquals(speedRight, compose.onNodeWithTag("sessionWorkSpeed").fetchSemanticsNode().boundsInRoot.right, 1f)
        compose.runOnIdle {
            detail.value = detail.value.copy(session = detail.value.session.copy(status = SessionStatus.Idle, tokensPerSecond = null))
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("sessionWorkSpeed").assertIsDisplayed()
        assertEquals(speedRight, compose.onNodeWithTag("sessionWorkSpeed").fetchSemanticsNode().boundsInRoot.right, 1f)
        compose.runOnIdle {
            detail.value = detail.value.copy(session = session)
            currentHost.value = host.copy(connection = ConnectionState.Offline())
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("sessionWorkSpeed").assertIsDisplayed()
        compose.runOnIdle { currentHost.value = host }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("sessionWorkSpeed").assertTextEquals("42 tok/s")
        compose.onAllNodes(hasText(target) and hasAnyAncestor(hasTestTag("sessionWorkStatus")))
            .assertCountEquals(0)
        compose.onNodeWithContentDescription("Fast mode").assertIsEnabled()

        // Before tools arrive, only the timeline shows an active-work label.
        compose.runOnIdle { detail.value = detail.value.copy(liveItems = emptyList()) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("timelineWorking").assertIsDisplayed()
        compose.onAllNodes(hasText("Thinking") and hasAnyAncestor(hasTestTag("sessionWorkStatus")))
            .assertCountEquals(0)
        compose.onNode(hasText("23s") and hasAnyAncestor(hasTestTag("sessionWorkStatus")))
            .assertIsDisplayed()
        compose.onNodeWithTag("sessionWorkSpeed").assertTextEquals("42 tok/s")

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
