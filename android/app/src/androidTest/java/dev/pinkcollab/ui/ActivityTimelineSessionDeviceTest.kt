package dev.pinkcollab.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pinkcollab.data.*
import dev.pinkcollab.ui.theme.Base0
import dev.pinkcollab.ui.theme.PinkCollabTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityTimelineSessionDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val host = HostState(PairedHost(Host("host", "Desktop", "", "", ""), "https://host", "credential", "client"),
        connection = ConnectionState.Online(1L))
    private val session = Session("session", "host", "/work/PinkCollab", "Refine session timeline", SessionStatus.Running,
        "", false, null, "", "", true, "generation")
    private val question = TimelineItem("question", "user", "Make the session timeline feel lighter and show each step as the agent works.",
        "", "2026-10-22T08:00:00Z")
    private fun tool(id: String, name: String, action: String, target: String, completed: Boolean = true) =
        TimelineItem("timeline-$id", "tool", "", "", "", ToolTrace(id, name, ToolArguments(), "", false, completed,
            summary = ToolSummary(action, target, emptyList(), ""), detailsAvailable = true, detailsVersion = "v1"))
    private val read = tool("read", "read", "Read the session presentation", "ui/SessionTimeline.kt")
    private val search = tool("search", "search", "Find timeline rendering and transitions", "ActivityGroup · SessionPage")
    private val change = tool("edit", "edit", "Refine the activity timeline", "ui/ActivityTimeline.kt", false)
    private fun detail(items: List<TimelineItem>) = SessionDetail(session, snapshotToken = "subscription",
        savedHistory = SavedHistory.Ready("history", emptyList(), null), liveItems = items)

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        context.getExternalFilesDir(null)!!.resolve("timeline-$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun actual_session_progresses_from_working_to_latest_step_to_folded_reply() {
        val state = mutableStateOf(detail(listOf(question)))
        compose.setContent {
            PinkCollabTheme {
                Box(Modifier.fillMaxSize().background(Base0).systemBarsPadding()) {
                    SessionPage(SessionPageState(LoadState.Ready(state.value), host, SessionDraft(), 0,
                        SessionActivity(), null, null), {}, { true })
                }
            }
        }
        compose.onNodeWithTag("timelineWorking").assertIsDisplayed()
        compose.runOnIdle { state.value = detail(listOf(question, read, search, change)) }
        compose.onNodeWithTag("activity-preview-edit").assertIsDisplayed()
        compose.onNodeWithTag("activity-preview-read").assertDoesNotExist()
        compose.onNodeWithTag("timelineWorking").assertDoesNotExist()
        screenshot("working")
        val reply = TimelineItem("reply", "assistant", "The timeline now follows the work as it happens.\n\n" +
            "- Current actions update in place.\n- Recent results open automatically.\n- Earlier steps stay available above the reply.", "", "2026-10-22T08:01:00Z")
        compose.runOnIdle {
            state.value = detail(listOf(question, read, search, change.copy(tool = change.tool!!.copy(completed = true)), reply))
                .copy(session = session.copy(status = SessionStatus.Idle))
        }
        compose.onNodeWithContentDescription("Expand activity").assertIsDisplayed()
        compose.onNodeWithTag("activity-preview-edit").assertDoesNotExist()
        compose.onNodeWithText("1 search · 1 read · 1 change").assertIsDisplayed()
        screenshot("reply")
        compose.onNodeWithContentDescription("Expand activity").performClick()
        compose.onNodeWithTag("activity-step-read").performScrollTo().performClick()
        compose.onNodeWithText("ui/SessionTimeline.kt").assertIsDisplayed()
        screenshot("expanded")
    }

    @Test fun previous_turn_and_disconnected_tools_never_drive_active_timeline() {
        val currentHost = mutableStateOf(host)
        val old = tool("old", "bash", "Old unfinished command", "old command", false)
        val state = mutableStateOf(detail(listOf(old, question, change)))
        compose.setContent {
            PinkCollabTheme {
                Box(Modifier.fillMaxSize().background(Base0).systemBarsPadding()) {
                    SessionPage(SessionPageState(LoadState.Ready(state.value), currentHost.value, SessionDraft(), 0,
                        SessionActivity(), null, null), {}, { true })
                }
            }
        }
        compose.onNodeWithTag("activity-step-old").assertDoesNotExist()
        compose.onNodeWithTag("activity-preview-edit").assertIsDisplayed()
        compose.runOnIdle { currentHost.value = host.copy(connection = ConnectionState.Offline()) }
        compose.onAllNodesWithContentDescription("Collapse activity").assertCountEquals(0)
        compose.onNodeWithTag("activity-preview-edit").assertDoesNotExist()
        compose.onNodeWithTag("timelineWorking").assertDoesNotExist()
    }

    @Test fun reply_collapses_all_manually_opened_groups_before_the_session_becomes_idle() {
        val progress = TimelineItem("progress", "assistant", (1..30).joinToString("\n") { "Inspecting the rendering code, line $it." }, "", "")
        val state = mutableStateOf(detail(listOf(question, read, progress, search, change)))
        compose.setContent {
            PinkCollabTheme {
                Box(Modifier.fillMaxSize().background(Base0).systemBarsPadding()) {
                    SessionPage(SessionPageState(LoadState.Ready(state.value), host, SessionDraft(), 0,
                        SessionActivity(), null, null), {}, { true })
                }
            }
        }
        // A user scroll leaves follow-latest mode before locating an older disclosure.
        compose.onNodeWithTag("sessionTimeline").performTouchInput { swipeDown() }
        compose.onNodeWithTag("sessionTimeline").performScrollToNode(hasText("1 read"))
        compose.onNodeWithContentDescription("Expand activity").performScrollTo().performClick()
        compose.onNodeWithTag("activity-step-read").performScrollTo().performClick()
        compose.onNodeWithTag("activity-preview-read").assertIsDisplayed()
        compose.onNodeWithTag("sessionTimeline").performScrollToNode(hasTestTag("activity-step-search"))
        compose.onNodeWithTag("activity-step-search").performScrollTo().performClick()
        compose.onNodeWithTag("activity-preview-search").assertIsDisplayed()

        val reply = TimelineItem("final", "assistant", "The timeline is ready.", "", "")
        compose.runOnIdle {
            state.value = detail(listOf(question, read, progress, search, change.copy(tool = change.tool!!.copy(completed = true)), reply))
        }
        compose.onAllNodesWithContentDescription("Collapse activity").assertCountEquals(0)
        compose.onNodeWithTag("sessionTimeline").performScrollToNode(hasText("1 search · 1 change"))
        compose.onNodeWithText("1 search · 1 change").assertIsDisplayed()
        compose.onNodeWithTag("activity-step-search").assertDoesNotExist()
        compose.onNodeWithTag("sessionTimeline").performScrollToNode(hasText("1 read"))
        compose.onNodeWithText("1 read").assertIsDisplayed()
        compose.onNodeWithTag("activity-step-read").assertDoesNotExist()
        compose.onNodeWithText("1 read").performClick()
        compose.onNodeWithTag("activity-preview-read").assertDoesNotExist()
        compose.onNodeWithTag("activity-step-read").performScrollTo().performClick()
        compose.runOnIdle { state.value = state.value.copy(liveItems = state.value.liveItems.dropLast(1) + reply.copy(text = "The timeline is ready. All done.")) }
        compose.onNodeWithTag("activity-preview-read").assertIsDisplayed()
    }

    @Test fun appended_steps_keep_the_latest_preview_clear_of_the_composer() {
        val state = mutableStateOf(detail(listOf(question, read)))
        compose.setContent {
            PinkCollabTheme {
                Box(Modifier.fillMaxSize().background(Base0).systemBarsPadding()) {
                    SessionPage(SessionPageState(LoadState.Ready(state.value), host, SessionDraft(), 0,
                        SessionActivity(), null, null), {}, { true })
                }
            }
        }
        compose.onNodeWithTag("activity-preview-read").assertIsDisplayed()
        compose.runOnIdle {
            state.value = detail(listOf(question, read) + (1..24).map {
                tool("step-$it", "bash", "Inspecting command $it", "command-$it", it != 24)
            })
        }
        val preview = compose.onNodeWithTag("activity-preview-step-24").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val status = compose.onNodeWithTag("sessionWorkStatus").fetchSemanticsNode().boundsInRoot
        assertTrue("Latest result must stay above the composer status", preview.bottom <= status.top)
    }

    @Test fun long_actions_and_targets_wrap_at_large_font_scale() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                PinkCollabTheme {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val renderer = remember { SessionMarkdownRenderer(createSessionMarkwon(context)) }
                    Column(Modifier.fillMaxWidth().background(Base0).statusBarsPadding()) {
                        DisplayItem(projectSessionTimeline(listOf(tool("long", "read",
                            "Read a long source path and inspect all the activity timeline transitions",
                            "/work/PinkCollab/android/app/src/main/java/dev/pinkcollab/ui/ActivityTimeline.kt", false))).single(),
                            renderer, liveActivity = true)
                    }
                }
            }
        }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val step = compose.onNodeWithTag("activity-step-long").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val preview = compose.onNodeWithTag("activity-preview-long").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(step.left >= root.left && step.right <= root.right)
        assertTrue(preview.left >= root.left && preview.right <= root.right)
        compose.onNodeWithText("Running", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Read a long source path and inspect all the activity timeline transitions, Running",
            useUnmergedTree = true).assertIsDisplayed()
        screenshot("large-font")
    }
}
