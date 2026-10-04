package dev.pinkcollab.ui

import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.CharacterStyle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseMarkdownDeviceTest {
    @Test fun inline_code_inherits_font_and_emphasis_through_links_after_optimization() {
        val parsed = ReleaseMarkdownProbe.render(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "**outer [`inner`](https://example.com)** then `plain`",
        )
        assertEquals("outer inner then plain", parsed.toString())
        val inheritedFont = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        fun paintFor(literal: String): TextPaint {
            val start = parsed.toString().indexOf(literal)
            return TextPaint().apply {
                typeface = inheritedFont
                textSize = 19f
                parsed.getSpans(start, start + literal.length, CharacterStyle::class.java)
                    .forEach { it.updateDrawState(this) }
            }
        }
        val plain = paintFor("plain")
        val outer = paintFor("outer")
        val inner = paintFor("inner")
        assertSame("Inline code inherits its surrounding font", inheritedFont, plain.typeface)
        for (paint in listOf(plain, inner)) {
            assertEquals("Inline code inherits its surrounding size", 19f, paint.textSize, 0f)
            assertEquals("Inline code does not introduce a background", 0, paint.bgColor)
        }
        assertEquals("Strong emphasis survives the intervening link", outer.color, inner.color)
        assertNotEquals("Plain code remains distinct from strong emphasis", plain.color, inner.color)
    }
}
