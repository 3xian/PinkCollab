package dev.pinkcollab.ui

import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pinkcollab.data.ToolArguments
import dev.pinkcollab.data.TodoPhase
import dev.pinkcollab.data.TodoTask
import dev.pinkcollab.data.TodoStatus
import dev.pinkcollab.data.ToolTrace
import dev.pinkcollab.ui.theme.PinkCollabTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodoQuestDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun focusExpansionAndCompletionRemainReadable() {
        val phases = listOf(
            TodoPhase("Investigate", listOf(TodoTask("Read the current implementation", TodoStatus.Completed))),
            TodoPhase("Build & verify", listOf(
                TodoTask("Design a dedicated todo panel with carefully staged completion feedback", TodoStatus.Active),
                TodoTask("Review on a small screen", TodoStatus.Blocked, "Waiting for device access"),
                TodoTask("Run verification", TodoStatus.Pending),
                TodoTask("Previous approach", TodoStatus.Abandoned),
            )),
        )
        val state = mutableStateOf(TodoPlan(phases,
            ToolTrace("todo", "todo", ToolArguments(), "Todo snapshot", false, true)))
        val expanded = mutableStateOf(false)
        compose.setContent { PinkCollabTheme {
            FloatingTodoPanel(state.value, live = true, expanded = expanded.value,
                onExpandedChange = { expanded.value = it }, maxHeight = 680.dp)
        } }
        // Infinite breathing is deliberate; drive the animation clock explicitly.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("todoHeader").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Review on a small screen · Waiting for device access").assertIsDisplayed()
        compose.onNodeWithText("Previous approach").assertIsDisplayed()
        saveScreenshot("todo-expanded.png")
        compose.runOnIdle {
            state.value = state.value.copy(phases = phases.map { phase -> phase.copy(tasks = phase.tasks.map {
                if (it.status == TodoStatus.Active) it.copy(status = TodoStatus.Completed) else it
            }) })
        }
        compose.mainClock.advanceTimeBy(750)
        compose.onNodeWithText("QUEST LOG · 2 completed · 1 dropped").assertIsDisplayed()
        compose.onNodeWithText("Details").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Arguments\n\n\nOutput\nTodo snapshot").assertExists()
    }

    @Test fun allTasksSettledAutoCollapsesWithoutReplayingOnHistoryMount() {
        val state = mutableStateOf(TodoPlan(listOf(TodoPhase("Ship",
            listOf(TodoTask("Build", TodoStatus.Active)))), ToolTrace("todo", "todo", ToolArguments(), "", false, true)))
        val expanded = mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { PinkCollabTheme {
            FloatingTodoPanel(state.value, live = true, expanded = expanded.value,
                onExpandedChange = { expanded.value = it }, maxHeight = 500.dp)
        } }
        compose.onNodeWithTag("todoHeader").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { state.value = state.value.copy(phases = listOf(TodoPhase("Ship",
            listOf(TodoTask("Build", TodoStatus.Completed))))) }
        compose.mainClock.advanceTimeBy(2400)
        compose.onNodeWithTag("todoHeader").assertIsDisplayed()
        compose.onNodeWithText("Plan settled").assertIsDisplayed()
        compose.onNodeWithText("QUEST LOG · 1 completed").assertDoesNotExist()
        compose.onNodeWithTag("todoHeader").performClick()
        compose.mainClock.advanceTimeBy(3000)
        compose.onNodeWithText("QUEST LOG · 1 completed").assertIsDisplayed()
    }

    private fun saveScreenshot(name: String) {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PinkCollabQA")
        })!!
        resolver.openOutputStream(uri)!!.use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
