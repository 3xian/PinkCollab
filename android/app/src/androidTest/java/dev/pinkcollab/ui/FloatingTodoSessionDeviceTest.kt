package dev.pinkcollab.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pinkcollab.data.*
import dev.pinkcollab.ui.theme.PinkCollabTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FloatingTodoSessionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val session = Session("demo", "desktop", "/work", "Task plan", SessionStatus.Running,
        "", false, null, "", "", true, "generation")
    private val host = HostState(PairedHost(Host("desktop", "Desktop", "", "", ""), "", "", ""),
        connection = ConnectionState.Online(1L))
    private fun message(index: Int) = TimelineItem("message-$index", "assistant", "Step $index: regular execution output", "", "")
    private fun todo(id: String, cleared: Boolean = false) = TimelineItem(id, "tool", "Finished · todo", "", "",
        ToolTrace(id, "todo", ToolArguments(), if (cleared) "Todo list cleared." else """
            Overall: 0/3 done, 3 open.
              Build:
                - [ ] Inspect the implementation (in progress)
                - [ ] Implement changes
                - [ ] Verify in emulator
        """.trimIndent(), false, true, if (cleared) emptyList() else listOf(TodoPhase("Build", listOf(
            TodoTask("Inspect the implementation", TodoStatus.Active),
            TodoTask("Implement changes", TodoStatus.Pending),
            TodoTask("Verify in emulator", TodoStatus.Pending),
        )))))

    @Test fun panelStaysPinnedAndExpansionDoesNotMoveMessagesOrCoverComposer() {
        val items = mutableListOf(message(0), todo("plan"), message(1))
        val detail = mutableStateOf(SessionDetail(session, snapshotToken = "sub", liveItems = items.toList()))
        compose.mainClock.autoAdvance = false
        compose.setContent { PinkCollabTheme {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
                SessionPage(SessionPageState(LoadState.Ready(detail.value), host, SessionDraft(), 0,
                    SessionActivity(), null, null), {}, { true })
            }
        } }
        compose.mainClock.advanceTimeBy(500)
        val header = compose.onNodeWithTag("todoHeader")
        val initialBounds = header.fetchSemanticsNode().boundsInRoot
        items += (2..20).map(::message)
        compose.runOnIdle { detail.value = detail.value.copy(liveItems = items.toList()) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
        header.assertIsDisplayed()
        assertEquals(initialBounds, header.fetchSemanticsNode().boundsInRoot)
        saveScreenshot("todo-floating-collapsed.png")
        // Markdown is rendered by a native TextView outside the Compose semantics tree.
        compose.runOnIdle {
            assertTrue(compose.activity.window.decorView.hasVisibleText("Step 20: regular execution output"))
        }
        val timeline = compose.onNodeWithTag("sessionTimeline")
        val timelineBounds = timeline.fetchSemanticsNode().boundsInRoot
        header.performClick()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(timelineBounds, timeline.fetchSemanticsNode().boundsInRoot)
        val panelBottom = compose.onNodeWithTag("floatingTodoPanel").fetchSemanticsNode().boundsInRoot.bottom
        val composerTop = compose.onNodeWithTag("sessionInput").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Expanded plan must stop above the composer", panelBottom < composerTop)
        compose.onNodeWithText("Implement changes").assertIsDisplayed()
        saveScreenshot("todo-floating-expanded.png")
        header.performClick()
        compose.mainClock.advanceTimeBy(500)
        timeline.performTouchInput { swipeDown() }
        compose.mainClock.advanceTimeBy(300)
        header.assertIsDisplayed()
        assertEquals(initialBounds, header.fetchSemanticsNode().boundsInRoot)
        // Switching to a session without a plan must not reuse the previous session's panel.
        compose.runOnIdle { detail.value = detail.value.copy(session = session.copy(id = "other"), liveItems = listOf(message(21))) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("floatingTodoPanel").assertDoesNotExist()
    }

    @Test fun clearedLiveSnapshotRemovesPinnedPlan() {
        val detail = mutableStateOf(SessionDetail(session, snapshotToken = "sub", liveItems = listOf(todo("plan"))))
        compose.mainClock.autoAdvance = false
        compose.setContent { PinkCollabTheme {
            SessionPage(SessionPageState(LoadState.Ready(detail.value), host, SessionDraft(), 0,
                SessionActivity(), null, null), {}, { true })
        } }
        compose.onNodeWithTag("todoHeader").assertIsDisplayed()
        compose.runOnIdle { detail.value = detail.value.copy(liveItems = detail.value.liveItems + todo("clear", true)) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("floatingTodoPanel").assertDoesNotExist()
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

    private fun View.hasVisibleText(text: String): Boolean {
        if (this is TextView && this.text.toString() == text && getGlobalVisibleRect(Rect())) return true
        return this is ViewGroup && (0 until childCount).any { getChildAt(it).hasVisibleText(text) }
    }
}
