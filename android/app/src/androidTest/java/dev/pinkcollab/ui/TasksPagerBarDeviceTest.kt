package dev.pinkcollab.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pinkcollab.data.AppState
import dev.pinkcollab.data.Host
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.PairedHost
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SessionKey
import dev.pinkcollab.data.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class TasksPagerBarDeviceTest {
    @get:Rule val compose = createComposeRule()

    private val sessions = (0..5).map { index ->
        Session("s$index", "host", "/work/project-$index/", "Session $index",
            if (index == 2) SessionStatus.NeedsInput else SessionStatus.Idle, "", false,
            null, "2026-09-${(28 - index).toString().padStart(2, '0')}T00:00:00Z", "", false)
    }

    @Test fun cards_scroll_freely_and_selection_tracks_timeline_swipes() {
        var selected by mutableStateOf<SessionKey?>(SessionKey("host", "s0"))
        val host = HostState(PairedHost(Host("host", "Desktop", "", "", ""), "", "", ""), sessions = sessions)
        compose.setContent {
            TasksScreen(
                TasksScreenState(AppState(hosts = mapOf("host" to host)), emptyMap(), emptyMap(),
                    emptySet(), emptyMap(), emptyMap(), emptyMap(), selected),
                TasksScreenActions({ selected = it }, {}, {}, {}, {}, { _, _ -> }, { _, _ -> true }),
            )
        }
        compose.waitForIdle()
        val strip = compose.onNodeWithTag("sessionCards").fetchSemanticsNode().boundsInRoot
        val first = compose.onNodeWithTag("sessionCard:s0").fetchSemanticsNode().boundsInRoot
        assertTrue("First card stays at the start", abs(first.left - strip.left - 12 * compose.density.density) <= 2f)
        assertEquals(listOf("Session 0", "project-0", "Paused"),
            compose.onNodeWithTag("sessionCard:s0").fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text })
        assertTrue("Compact two-line strip", abs(strip.height - 64 * compose.density.density) <= 2f)

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
        assertEquals(listOf("Session 2", "project-2", "Needs you"),
            compose.onNodeWithTag("sessionCard:s2").fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text })
        assertCentered("s2")

        compose.onNodeWithTag("sessionTimelinePager").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(SessionKey("host", "s3"), selected)
        assertCentered("s3")
    }

    private fun assertCentered(id: String) {
        val strip = compose.onNodeWithTag("sessionCards").fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("sessionCard:$id").fetchSemanticsNode().boundsInRoot
        assertTrue("$id centered: ${card.center.x} vs ${strip.center.x}",
            abs(card.center.x - strip.center.x) <= 2f)
    }
}
