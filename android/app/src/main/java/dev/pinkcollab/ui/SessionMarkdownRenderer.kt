package dev.pinkcollab.ui

import android.text.Spanned
import android.text.SpannedString
import io.noties.markwon.Markwon

internal class SessionMarkdownRenderer(val markwon: Markwon) {
    private val cache = TextRenderCache { text -> SpannedString(markwon.toMarkdown(text)) }

    suspend fun render(text: String): Spanned = cache.get(text)
}
