package dev.pinkcollab.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pinkcollab.data.AppState
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.Host
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.PairedHost
import dev.pinkcollab.data.Workspace
import dev.pinkcollab.ui.theme.PinkCollabTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompactLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrow_header_reserves_complete_title_at_double_font_scale() {
        var refreshes = 0
        var usageOpens = 0
        compose.setContent {
            val density = LocalDensity.current
            PinkCollabTheme {
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    Box(Modifier.width(280.dp)) {
                        ModelPickerHeader({ usageOpens++ }, { refreshes++ }, true, true)
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        val heading = compose.onNodeWithText("Models").assertIsDisplayed()
        heading.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertFalse("Title must remain complete", layouts.single().hasVisualOverflow)
        val bounds = heading.fetchSemanticsNode().boundsInRoot
        for (label in listOf("Reload", "Usage")) {
            val action = compose.onNodeWithText(label).assertIsDisplayed()
            assertFalse(bounds.overlaps(action.fetchSemanticsNode().boundsInRoot))
            action.performClick()
        }
        assertEquals(1, refreshes)
        assertEquals(1, usageOpens)
    }

    @Test fun long_workspace_path_is_complete_and_opens_exact_directory() {
        val path = "/home/developer/projects/shared-prefix/very-long-workspace-directory/unique-tail"
        var opened: Pair<String, String>? = null
        val host = HostState(
            PairedHost(Host("host", "Host", "linux", "1.0", "0.2"), "https://example.test", "credential", "client"),
            connection = ConnectionState.Online(0),
            workspaces = listOf(Workspace("Hidden name", path)),
        )
        compose.setContent {
            PinkCollabTheme {
                Box(Modifier.width(280.dp)) {
                    ResourcesScreen(navigationState(AppState(hosts = mapOf("host" to host))),
                        hostBusy = { false }, browse = { id, directory -> opened = id to directory },
                        pair = {}, refresh = {}, rename = { _, _ -> }, forget = {})
                }
            }
        }
        compose.onNodeWithText("Hidden name").assertDoesNotExist()
        val layouts = mutableListOf<TextLayoutResult>()
        val directory = compose.onNodeWithText(path).assertIsDisplayed()
        directory.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse("Directory tail must not be truncated", layout.hasVisualOverflow)
        assertTrue("Long path must wrap", layout.lineCount > 1)
        assertEquals(path.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
        directory.performClick()
        assertEquals("host" to path, opened)
    }
}
