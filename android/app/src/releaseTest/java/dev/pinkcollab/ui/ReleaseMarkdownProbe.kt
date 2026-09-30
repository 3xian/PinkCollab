package dev.pinkcollab.ui

import android.content.Context
import android.text.Spanned
import androidx.annotation.Keep
import kotlinx.coroutines.runBlocking

/** Test-only entry point: keep the JVM boundary, not the renderer or Markwon internals. */
@Keep
internal object ReleaseMarkdownProbe {
    fun render(context: Context, markdown: String): Spanned = runBlocking {
        SessionMarkdownRenderer(createSessionMarkwon(context)).render(markdown)
    }
}
