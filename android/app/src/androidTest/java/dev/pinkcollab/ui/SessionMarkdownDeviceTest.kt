package dev.pinkcollab.ui

import android.text.Spanned
import android.graphics.Bitmap
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
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
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
import io.noties.markwon.ext.tables.TableRowSpan
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
            assertFalse(view.isTextSelectable)
            assertTrue(parsed.toString().contains("val answer = 42"))
            assertTrue(parsed.getSpans(0, parsed.length, io.noties.markwon.core.spans.LinkSpan::class.java).isNotEmpty())
            assertTrue(view.performLongClick())
            val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            assertEquals(text.value, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            text.value = "**Updated** reply"
        }
        compose.waitUntil(10_000) {
            compose.runOnIdle { markdownView()?.text?.toString() == "Updated reply" }
        }
    }

    @Test fun tables_render_aligned_formatted_cells_and_copy_source_through_updates() {
        val source = """
            Results

            | Item | Status | Count |
            | :--- | :---: | ---: |
            | **Android** | [Ready](https://example.com) | 42 |
            | Gateway | `running` | 7 |

            After the table.
        """.trimIndent()
        val text = mutableStateOf(source)
        val renderer = SessionMarkdownRenderer(createSessionMarkwon(compose.activity))
        compose.setContent {
            MaterialTheme(colorScheme = PinkCollabScheme, typography = SessionTypography) {
                androidx.compose.material3.Surface {
                    DisplayItem(SessionDisplayItem.Message("table", "assistant", text.value, ""), renderer)
                }
            }
        }
        fun rows(): List<TableRowSpan> {
            val parsed = markdownView()?.text as? Spanned ?: return emptyList()
            return parsed.getSpans(0, parsed.length, TableRowSpan::class.java).toList()
        }
        compose.waitUntil(10_000) {
            compose.runOnIdle {
                rows().size == 3 && rows().all { it.findLayoutForHorizontalOffset(0) != null }
            }
        }
        compose.runOnIdle {
            val view = requireNotNull(markdownView())
            val bodyRow = rows()[1]
            val first = requireNotNull(bodyRow.findLayoutForHorizontalOffset(0))
            val middle = requireNotNull(bodyRow.findLayoutForHorizontalOffset(view.width / 2))
            val last = requireNotNull(bodyRow.findLayoutForHorizontalOffset(view.width * 5 / 6))
            assertEquals("Android", first.text.toString())
            assertEquals("Short cells must use the available message width without wrapping", 1, first.lineCount)
            assertEquals(android.text.Layout.Alignment.ALIGN_NORMAL, first.alignment)
            assertEquals("Ready", middle.text.toString())
            assertEquals(android.text.Layout.Alignment.ALIGN_CENTER, middle.alignment)
            assertEquals("42", last.text.toString())
            assertEquals(android.text.Layout.Alignment.ALIGN_OPPOSITE, last.alignment)
            val linkedCell = middle.text as Spanned
            assertEquals(1, linkedCell.getSpans(0, linkedCell.length, io.noties.markwon.core.spans.LinkSpan::class.java).size)
            assertTrue(view.text.toString().contains("After the table."))
            assertTrue(view.performLongClick())
            val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            assertEquals(source, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        compose.activity.getExternalFilesDir(null)!!.resolve("markdown-table.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.runOnIdle { text.value = source.replace("| Gateway | `running` | 7 |", "| Gateway | `running` | 7 |\n| Website | Done | 3 |") }
        compose.waitUntil(10_000) { compose.runOnIdle { rows().size == 4 } }
        compose.runOnIdle { text.value = "Plain reply after the table" }
        compose.waitUntil(10_000) {
            compose.runOnIdle { markdownView()?.text?.toString() == "Plain reply after the table" && rows().isEmpty() }
        }
    }

    @Test fun markup_with_no_rendered_text_keeps_the_source_visible() {
        val source = "<!-- pending -->"
        val renderer = SessionMarkdownRenderer(createSessionMarkwon(compose.activity))
        compose.setContent {
            MaterialTheme {
                DisplayItem(SessionDisplayItem.Message("pending", "assistant", source, ""), renderer)
            }
        }
        compose.waitUntil(10_000) {
            compose.runOnIdle { markdownView()?.text?.toString() == source }
        }
    }

    @Test fun identical_tables_keep_independent_widths_when_one_message_is_removed() {
        val showNarrow = mutableStateOf(true)
        val source = "| Item |\n| --- |\n| A long table cell with enough words to wrap in the narrow message |"
        val renderer = SessionMarkdownRenderer(createSessionMarkwon(compose.activity))
        compose.setContent {
            MaterialTheme {
                Column {
                    if (showNarrow.value) {
                        Box(Modifier.width(160.dp)) {
                            DisplayItem(SessionDisplayItem.Message("narrow", "assistant", source, ""), renderer)
                        }
                    }
                    Box(Modifier.width(280.dp)) {
                        DisplayItem(SessionDisplayItem.Message("wide", "assistant", source, ""), renderer)
                    }
                }
            }
        }
        fun bodyLayout(view: TextView): android.text.Layout? {
            val parsed = view.text as? Spanned ?: return null
            return parsed.getSpans(0, parsed.length, TableRowSpan::class.java)
                .mapNotNull { it.findLayoutForHorizontalOffset(0) }
                .firstOrNull { it.text.toString().startsWith("A long table cell") }
        }
        fun tableViews(): List<TextView> =
            markdownViews(compose.activity.window.decorView).filter { bodyLayout(it) != null }
        compose.waitUntil(10_000) { compose.runOnIdle { tableViews().size == 2 } }
        val wideLines = compose.runOnIdle {
            val views = tableViews()
            val narrow = requireNotNull(bodyLayout(views[0]))
            val wide = requireNotNull(bodyLayout(views[1]))
            assertTrue("Narrow message must wrap independently", narrow.lineCount > wide.lineCount)
            assertTrue("Each table must use its own message width", narrow.width < wide.width)
            wide.lineCount
        }
        compose.runOnIdle { showNarrow.value = false }
        compose.waitUntil(10_000) { compose.runOnIdle { tableViews().size == 1 } }
        compose.runOnIdle { assertEquals(wideLines, requireNotNull(bodyLayout(tableViews().single())).lineCount) }
    }

    @Test fun table_cells_reflow_when_typography_changes_without_a_width_change() {
        val enlarged = mutableStateOf(false)
        val renderer = SessionMarkdownRenderer(createSessionMarkwon(compose.activity))
        val source = "| Item |\n| --- |\n| A long table cell with enough words to wrap differently when the font grows |"
        compose.setContent {
            val typography = SessionTypography.copy(
                bodyMedium = SessionTypography.bodyMedium.copy(fontSize = if (enlarged.value) 24.sp else 12.sp),
            )
            MaterialTheme(typography = typography) {
                Box(Modifier.width(240.dp)) {
                    DisplayItem(SessionDisplayItem.Message("table", "assistant", source, ""), renderer)
                }
            }
        }
        fun bodyLayout(): android.text.Layout? {
            val parsed = markdownView()?.text as? Spanned ?: return null
            return parsed.getSpans(0, parsed.length, TableRowSpan::class.java)
                .mapNotNull { it.findLayoutForHorizontalOffset(0) }
                .firstOrNull { it.text.toString().startsWith("A long table cell") }
        }
        compose.waitUntil(10_000) { compose.runOnIdle { bodyLayout() != null } }
        val original = compose.runOnIdle {
            val view = requireNotNull(markdownView())
            val cell = requireNotNull(bodyLayout())
            assertEquals(view.textSize, cell.paint.textSize, 0.1f)
            Triple(view.width, cell.lineCount, cell.height)
        }
        val originalSize = compose.runOnIdle { requireNotNull(markdownView()).textSize }
        compose.runOnIdle { enlarged.value = true }
        compose.waitUntil(10_000) {
            compose.runOnIdle {
                val view = requireNotNull(markdownView())
                val cell = bodyLayout()
                cell != null && view.textSize > originalSize &&
                    kotlin.math.abs(cell.paint.textSize - view.textSize) < 0.1f
            }
        }
        compose.runOnIdle {
            assertEquals(original.first, requireNotNull(markdownView()).width)
            val cell = requireNotNull(bodyLayout())
            assertTrue("Larger text must wrap onto more lines", cell.lineCount > original.second)
            assertTrue(cell.height > original.third)
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

    @Test fun narrow_message_uses_full_body_width_and_copies_on_long_press() {
        val message = "A longer message that wraps on a narrow screen.\nFinal line."
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(200.dp).testTag("messageBody")) {
                    TimelineMessageBody(message) { _ ->
                        Text(message, Modifier.fillMaxWidth().testTag("bodyText"))
                    }
                }
            }
        }
        val container = compose.onNodeWithTag("messageBody").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("bodyText").fetchSemanticsNode().boundsInRoot
        assertEquals(container.left, body.left, 0.5f)
        assertEquals(container.right, body.right, 0.5f)
        assertEquals(container.top, body.top, 0.5f)
        assertEquals(container.bottom, body.bottom, 0.5f)
        compose.onNodeWithContentDescription("Copy message").assertDoesNotExist()
        compose.onNodeWithTag("bodyText").performTouchInput { longClick() }
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

    private fun markdownViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { markdownViews(view.getChildAt(it)) }
        else -> emptyList()
    }

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
