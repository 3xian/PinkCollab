package dev.pinkcollab.ui

import android.text.Spanned
import android.content.ClipboardManager
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import dev.pinkcollab.ui.theme.SessionTypography
import dev.pinkcollab.ui.theme.PinkCollabScheme
import dev.pinkcollab.data.TimelineItem
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SessionMarkdownDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun background_markdown_keeps_formatting_links_and_updates_the_current_message() {
        val text = mutableStateOf("**Ready** [Docs](https://example.com) `code`\n\n```kotlin\nval answer = 42\n```")
        val renderer = SessionMarkdownRenderer(createSessionMarkwon(compose.activity))
        compose.setContent {
            MaterialTheme {
                DisplayItem(SessionDisplayItem.Message("message", "assistant", text.value, ""), renderer)
            }
        }
        compose.waitUntil(10_000) {
            compose.runOnIdle {
                markdownView()?.text?.toString()?.let { "Ready Docs code" in it && "**" !in it } == true
            }
        }
        compose.runOnIdle {
            val view = requireNotNull(markdownView())
            val parsed = view.text as Spanned
            assertTrue(view.isTextSelectable)
            assertTrue(parsed.toString().contains("val answer = 42"))
            assertTrue(parsed.getSpans(0, parsed.length, io.noties.markwon.core.spans.LinkSpan::class.java).isNotEmpty())
            text.value = "**Updated** reply"
        }
        compose.waitUntil(10_000) {
            compose.runOnIdle { markdownView()?.text?.toString() == "Updated reply" }
        }
    }

    @Test fun slow_replacements_keep_the_previous_formatting_and_finish_with_the_latest_text() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val markwon = Markwon.builder(compose.activity)
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun processMarkdown(markdown: String): String {
                    if (markdown == "**Pending** reply") {
                        started.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "Render was not released" }
                    }
                    return markdown
                }
            }).build()
        val renderer = SessionMarkdownRenderer(markwon)
        val text = mutableStateOf("**Ready** reply")
        compose.setContent {
            MaterialTheme {
                DisplayItem(SessionDisplayItem.Message("message", "assistant", text.value, ""), renderer)
            }
        }
        try {
            compose.waitUntil(5_000) {
                compose.runOnIdle { markdownView()?.text?.toString() == "Ready reply" }
            }
            compose.runOnIdle { text.value = "**Pending** reply" }
            compose.waitUntil(5_000) { started.count == 0L }
            compose.runOnIdle {
                assertEquals("Ready reply", markdownView()?.text?.toString())
                text.value = "**Latest** reply"
            }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals("Ready reply", markdownView()?.text?.toString()) }
            release.countDown()
            compose.waitUntil(5_000) {
                compose.runOnIdle { markdownView()?.text?.toString() == "Latest reply" }
            }
        } finally {
            release.countDown()
        }
    }

    @Test fun narrow_message_uses_full_body_width_and_copies_without_covering_text() {
        val message = "A longer message that wraps on a narrow screen.\nFinal line."
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(200.dp).testTag("messageBody")) {
                    TimelineMessageBody(message) {
                        Text(message, Modifier.fillMaxWidth().testTag("bodyText"))
                    }
                }
            }
        }
        val container = compose.onNodeWithTag("messageBody").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("bodyText").fetchSemanticsNode().boundsInRoot
        val copy = compose.onNodeWithContentDescription("Copy message")
        val button = copy.fetchSemanticsNode().boundsInRoot
        assertEquals(container.left, body.left, 0.5f)
        assertEquals(container.right, body.right, 0.5f)
        assertTrue("Copy action must not overlap the final text line", button.top >= body.bottom)
        copy.performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            assertEquals(message, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
    }

    @Test fun user_and_agent_bodies_keep_equal_sizes_when_typography_changes() {
        val compact = mutableStateOf(false)
        val renderer = SessionMarkdownRenderer(createSessionMarkwon(compose.activity))
        compose.setContent {
            val typography = if (compact.value) SessionTypography else SessionTypography.copy(
                bodyMedium = SessionTypography.bodyMedium.copy(
                    fontSize = (SessionTypography.bodyMedium.fontSize.value + 2f).sp,
                ),
            )
            MaterialTheme(colorScheme = PinkCollabScheme, typography = typography) {
                Column {
                    DisplayItem(SessionDisplayItem.Message("user", "user", "User prose", ""), renderer)
                    DisplayItem(SessionDisplayItem.Message("agent", "assistant", "Agent prose", ""), renderer)
                    DisplayItem(SessionDisplayItem.Raw("tool", TimelineItem("tool", "tool", "Tool prose", "", "", null)), renderer)
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.runOnIdle { markdownView()?.text?.toString() == "Agent prose" }
        }
        fun composeTextSizePx(text: String): Float {
            val layouts = mutableListOf<TextLayoutResult>()
            val action = compose.onNodeWithText(text).fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult].action!!
            assertTrue(action(layouts))
            return android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                layouts.single().layoutInput.style.fontSize.value,
                compose.activity.resources.displayMetrics,
            )
        }
        val originalView = compose.runOnIdle { requireNotNull(markdownView()) }
        val originalSize = originalView.textSize
        assertEquals(composeTextSizePx("User prose"), originalSize, 0.1f)
        compose.runOnIdle { compact.value = true }
        compose.waitForIdle()
        val userSize = composeTextSizePx("User prose")
        assertEquals(userSize, composeTextSizePx("Tool prose"), 0.1f)
        compose.runOnIdle {
            val view = requireNotNull(markdownView())
            assertSame(originalView, view)
            assertTrue("Existing Markdown must shrink with the conversation", view.textSize < originalSize)
            assertEquals(userSize, view.textSize, 0.1f)
        }
    }

    private fun markdownView(): TextView? = findTextView(compose.activity.window.decorView)

    private fun findTextView(view: View): TextView? {
        if (view is TextView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findTextView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
