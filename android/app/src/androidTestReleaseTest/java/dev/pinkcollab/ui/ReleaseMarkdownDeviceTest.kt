package dev.pinkcollab.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseMarkdownDeviceTest {
    @Test fun inline_code_keeps_foreground_only_after_background_rendering() {
        val parsed = ReleaseMarkdownProbe.render(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "Plain `code` and **`bold`**",
        )
        // Assert the rendered palette without keeping production theme accessors alive.
        assertInlineCodeFormatting(parsed, 0xFFCFC3F7.toInt(), 0xFFC069C9.toInt())
    }
}
