package dev.pinkcollab.ui

import android.text.Spanned
import android.text.SpannableString
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SessionMarkdownRenderer(val markwon: Markwon) {
    private val cache = TextRenderCache { text -> markwon.parse(text) }
    private val renderMutex = Mutex()

    suspend fun render(text: String): Spanned = withContext(Dispatchers.Default) {
        // Keep parser/plugins serialized, but never share view-bound rendered spans.
        renderMutex.withLock {
            val spans = markwon.render(cache.get(text))
            // Preserve Markwon.toMarkdown's default behavior for empty rendered output.
            if (spans.isEmpty()) SpannableString(text) else spans
        }
    }
}
