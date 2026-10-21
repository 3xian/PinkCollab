package dev.pinkcollab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pinkcollab.data.ToolDetailPage
import dev.pinkcollab.data.item
import org.json.JSONObject
import dev.pinkcollab.ui.theme.PinkCollabTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityGroupDeviceTest {
    @get:Rule val compose = createComposeRule()

    private fun operation(index: Int, status: ActivityStatus = ActivityStatus.Succeeded) = ActivityOperation(
        "op-$index", "bash", "Command $index", status,
        error = if (status == ActivityStatus.Failed) "Access denied" else "",
        targets = listOf("src/Command$index.kt"),
    )

    private fun group(operations: List<ActivityOperation>) = SessionDisplayItem.ActivityGroup(
        "activity", operations.lastOrNull { it.error.isNotBlank() }?.error.orEmpty(), operations,
    )

    private fun mount(state: State<SessionDisplayItem.ActivityGroup>, active: State<Boolean> = mutableStateOf(false),
        controller: ToolDetailsController? = null) {
        compose.setContent {
            PinkCollabTheme {
                val context = LocalContext.current
                val renderer = remember { SessionMarkdownRenderer(createSessionMarkwon(context)) }
                LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
                    item { DisplayItem(state.value, renderer, liveActivity = active.value, toolDetails = controller) }
                }
            }
        }
    }

    @Test fun edit_summary_shows_file_and_change_without_a_detail_request() {
        var reads = 0
        val controller = ToolDetailsController { _, _ ->
            reads++
            ToolDetailPage("Full patch", "v1", null, true)
        }
        val wire = JSONObject("""{"id":"edit","kind":"tool","text":"","timestamp":"","tool":{"callId":"edit","name":"edit","completed":true,"summary":{"action":"Edit notes.md","target":"","files":["/tmp/demo/notes.md"],"error":"","preview":"+DONE"},"detailsAvailable":true,"detailsVersion":"v1"}}""")
        val display = projectSessionTimeline(listOf(wire.item())).single() as SessionDisplayItem.ActivityGroup
        mount(mutableStateOf(display), controller = controller)
        compose.onNodeWithContentDescription("Expand activity").performClick()
        compose.onNodeWithText("Edit notes.md").assertIsDisplayed()
        compose.onNodeWithTag("activity-step-edit").performClick()
        compose.onNodeWithText("/tmp/demo/notes.md").assertIsDisplayed()
        compose.onNodeWithText("+DONE").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, reads) }
    }

    @Test fun settled_work_starts_folded_and_previews_are_independent() {
        mount(mutableStateOf(group(listOf(operation(0), operation(1, ActivityStatus.Failed)))))
        compose.onNodeWithText("2 commands").assertIsDisplayed()
        compose.onNodeWithText("1 failed · Access denied").assertIsDisplayed()
        compose.onNodeWithTag("activity-step-op-0").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand activity").performClick()
        compose.onNodeWithContentDescription("Command 0, Completed", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Command 1, Failed", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("activity-step-op-0").performClick()
        compose.onNodeWithText("src/Command0.kt").assertIsDisplayed()
        compose.onNodeWithText("Completed").assertDoesNotExist()
        compose.onNodeWithText("Failed").assertDoesNotExist()
        compose.onNodeWithText("View details").assertDoesNotExist()
        compose.onNodeWithText("Hide details").assertDoesNotExist()
        compose.onNodeWithContentDescription("Collapse activity").performClick()
    }

    @Test fun live_steps_reveal_latest_preview_then_fold_on_settlement_without_fetching_output() {
        val active = mutableStateOf(true)
        var reads = 0
        val controller = ToolDetailsController { _, _ ->
            reads++
            ToolDetailPage("Remote output", "v1", null, true)
        }
        val state = mutableStateOf(group(listOf(operation(0))))
        mount(state, active, controller)
        compose.onNodeWithContentDescription("Collapse activity").assertIsDisplayed()
        compose.onNodeWithTag("activity-preview-op-0").assertIsDisplayed()
        compose.runOnIdle { state.value = group(listOf(operation(0), operation(1))) }
        compose.onNodeWithTag("activity-preview-op-0").assertDoesNotExist()
        compose.onNodeWithTag("activity-preview-op-1").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, reads) }
        compose.onNodeWithText("View details").assertDoesNotExist()
        compose.onNodeWithTag("activity-step-op-0").performClick()
        compose.onNodeWithTag("activity-preview-op-0").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, reads) }
        compose.onNodeWithText("Remote output").assertDoesNotExist()
        compose.runOnIdle { active.value = false }
        compose.onNodeWithContentDescription("Expand activity").assertIsDisplayed()
        compose.onNodeWithText("Remote output").assertDoesNotExist()
    }

    @Test fun manually_folded_live_sequence_stays_folded_when_more_tools_arrive() {
        val state = mutableStateOf(group(listOf(operation(0, ActivityStatus.Running))))
        mount(state, mutableStateOf(true))
        compose.onNodeWithContentDescription("Collapse activity").performClick()
        compose.onNodeWithText("1 running").assertIsDisplayed()
        compose.runOnIdle { state.value = group(listOf(operation(0), operation(1))) }
        compose.onNodeWithContentDescription("Expand activity").assertIsDisplayed()
        compose.onNodeWithTag("activity-step-op-1").assertDoesNotExist()
        compose.onNodeWithContentDescription("Expand activity").performClick()
        compose.onNodeWithTag("activity-preview-op-1").assertIsDisplayed()
    }

    @Test fun manually_opened_earlier_preview_survives_new_steps() {
        val state = mutableStateOf(group(listOf(operation(0), operation(1))))
        mount(state, mutableStateOf(true))
        compose.onNodeWithTag("activity-step-op-0").performClick()
        compose.onNodeWithTag("activity-preview-op-0").assertIsDisplayed()
        compose.runOnIdle { state.value = group(listOf(operation(0), operation(1), operation(2))) }
        compose.onNodeWithTag("activity-preview-op-0").assertIsDisplayed()
        compose.onNodeWithTag("activity-preview-op-1").assertDoesNotExist()
    }

    @Test fun long_work_sequence_keeps_every_operation_accessible() {
        mount(mutableStateOf(group((0..40).map { operation(it) })))
        compose.onNodeWithContentDescription("Expand activity").performClick()
        compose.onNodeWithTag("activity-step-op-0").assertIsDisplayed()
        compose.onNodeWithTag("activity-step-op-40").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("src/Command40.kt").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("View details").assertDoesNotExist()
    }

    @Test fun retained_unfinished_tools_show_uncertainty_and_no_live_disclosure() {
        mount(mutableStateOf(group(listOf(operation(0, ActivityStatus.Running)))))
        compose.onNodeWithContentDescription("Expand activity").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Command 0, Last seen running", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("activity-preview-op-0").assertDoesNotExist()
        compose.onNodeWithTag("activity-step-op-0").performClick()
        compose.onNodeWithText("Last seen running").assertDoesNotExist()
        compose.onNodeWithText("src/Command0.kt").assertIsDisplayed()
    }

    @Test fun tools_without_payloads_have_no_empty_preview_controls() {
        val op = operation(0).copy(targets = emptyList())
        mount(mutableStateOf(group(listOf(op))))
        compose.onNodeWithContentDescription("Expand activity").performClick()
        compose.onNodeWithContentDescription("Command 0, Completed", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("activity-step-op-0").assertHasNoClickAction()
        compose.onNodeWithText("View details").assertDoesNotExist()
    }
}
