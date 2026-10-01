package dev.pinkcollab.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pinkcollab.ui.theme.PinkCollabTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityGroupDeviceTest {
    @get:Rule val compose = createComposeRule()

    private fun operation(index: Int, status: ActivityStatus) = ActivityOperation(
        "op-$index", "bash", "Command $index", "", status, "Output $index", ActivityDetailKind.Operation,
    )

    private fun group(operations: List<ActivityOperation>) = SessionDisplayItem.ActivityGroup(
        "activity", ActivityStage.Execute, operations.size, emptyList(), ActivityStatus.Running,
        "Run commands", "Build and inspect the workspace", operations.count { it.status == ActivityStatus.Failed }, operations,
    )

    @Test fun mixed_outcomes_are_individual_horizontal_icons_and_details_toggle() {
        val item = group(listOf(operation(0, ActivityStatus.Succeeded), operation(1, ActivityStatus.Failed),
            operation(2, ActivityStatus.Running)))
        compose.setContent {
            PinkCollabTheme {
                val context = LocalContext.current
                val renderer = remember(context) { SessionMarkdownRenderer(createSessionMarkwon(context)) }
                Column(Modifier.fillMaxWidth().statusBarsPadding()) { DisplayItem(item, renderer, liveActivity = false) }
            }
        }
        val check = compose.onNodeWithContentDescription("Command 0, Completed", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val error = compose.onNodeWithContentDescription("Command 1, Failed", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val pending = compose.onNodeWithContentDescription("Command 2, Last seen running", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(check.top, error.top, 1f)
        assertEquals(check.top, pending.top, 1f)
        assertTrue(check.left < error.left && error.left < check.right)
        assertTrue(error.left < pending.left && pending.left < error.right)
        assertTrue(check.top >= compose.onNodeWithText(item.summary, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.bottom)
        compose.onNodeWithContentDescription("Expand activity").performClick()
        compose.onNodeWithText("Command 1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Collapse activity").performClick()
        compose.onNodeWithText("Command 1", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun appended_operations_beyond_old_cap_scroll_into_view_and_remain_expandable() {
        val item = mutableStateOf(group(listOf(operation(0, ActivityStatus.Succeeded))))
        compose.setContent {
            PinkCollabTheme {
                val context = LocalContext.current
                val renderer = remember(context) { SessionMarkdownRenderer(createSessionMarkwon(context)) }
                Column(Modifier.fillMaxWidth().statusBarsPadding()) { DisplayItem(item.value, renderer) }
            }
        }
        compose.onNodeWithContentDescription("Command 0, Completed", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle {
            item.value = group((0..29).map { operation(it, if (it == 29) ActivityStatus.Failed else ActivityStatus.Succeeded) })
        }
        compose.onNodeWithContentDescription("Command 29, Failed", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Expand activity").assertIsDisplayed()
        compose.onNode(hasScrollAction(), useUnmergedTree = true).performTouchInput { swipeRight() }
        compose.onNodeWithContentDescription("Command 0, Completed", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Expand activity").assertIsDisplayed()
    }

    @Test fun expanding_a_tall_group_keeps_the_header_visible_and_tappable() {
        val item = group((0..29).map { operation(it, ActivityStatus.Succeeded) })
        compose.setContent {
            PinkCollabTheme {
                val context = LocalContext.current
                val renderer = remember(context) { SessionMarkdownRenderer(createSessionMarkwon(context)) }
                LazyColumn(Modifier.fillMaxSize().statusBarsPadding()) {
                    item { DisplayItem(item, renderer) }
                }
            }
        }
        compose.onNodeWithText(item.action, useUnmergedTree = true).assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithText(item.action, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Command 0", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(item.action, useUnmergedTree = true).assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithText("Command 0", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun card_padding_and_summary_toggle_expansion_without_intercepting_detail_controls() {
        val item = group(listOf(operation(0, ActivityStatus.Succeeded), operation(1, ActivityStatus.Failed)))
        compose.setContent {
            PinkCollabTheme {
                val context = LocalContext.current
                val renderer = remember(context) { SessionMarkdownRenderer(createSessionMarkwon(context)) }
                Column(Modifier.fillMaxWidth().statusBarsPadding()) { DisplayItem(item, renderer) }
            }
        }
        compose.onNodeWithContentDescription("Expand activity").performTouchInput {
            click(Offset(width - 2f, height - 2f))
        }
        compose.onNodeWithText("Command 0", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText("Arguments & output").onFirst().performClick()
        compose.onNodeWithText("Output 0", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Collapse activity").assertIsDisplayed()
        compose.onNodeWithText(item.summary, useUnmergedTree = true).performTouchInput { click() }
        compose.onNodeWithText("Command 0", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Output 0", useUnmergedTree = true).assertDoesNotExist()
    }
}
