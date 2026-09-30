package dev.pinkcollab.ui

import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pinkcollab.ui.theme.BrandPink
import dev.pinkcollab.ui.theme.Purple200
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SessionMarkdownDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun inline_code_keeps_foreground_only_after_background_rendering() = runBlocking {
        val renderer = SessionMarkdownRenderer(createSessionMarkwon(compose.activity))
        val parsed = renderer.render("Plain `code` and **`bold`**")
        assertInlineCodeFormatting(parsed, Purple200.toArgb(), BrandPink.toArgb())
    }

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

internal fun assertInlineCodeFormatting(parsed: Spanned, plainColor: Int, boldColor: Int) {
    assertEquals("Plain code and bold", parsed.toString())
    for ((literal, color) in listOf("code" to plainColor, "bold" to boldColor)) {
        val start = parsed.toString().indexOf(literal)
        val spans = parsed.getSpans(start, start + literal.length, CharacterStyle::class.java)
        assertTrue(spans.any { it is ForegroundColorSpan && it.foregroundColor == color })
        val paint = TextPaint().apply { textSize = 16f }
        spans.forEach { it.updateDrawState(paint) }
        assertEquals("$literal background", 0, paint.bgColor)
        assertEquals("$literal foreground", color, paint.color)
        assertEquals("$literal size", 16f, paint.textSize, 0f)
    }
}
