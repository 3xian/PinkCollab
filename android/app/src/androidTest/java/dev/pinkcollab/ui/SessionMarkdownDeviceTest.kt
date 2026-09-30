package dev.pinkcollab.ui

import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
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
