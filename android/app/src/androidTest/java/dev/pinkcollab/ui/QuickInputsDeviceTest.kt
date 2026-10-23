package dev.pinkcollab.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pinkcollab.data.ModelInfo
import dev.pinkcollab.data.QuickInput
import dev.pinkcollab.data.QuickInputStore
import dev.pinkcollab.ui.theme.PinkCollabTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File

@RunWith(AndroidJUnit4::class)
class QuickInputsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferencesName = "quick-input-test-${UUID.randomUUID()}"
    private val store = QuickInputStore(context, preferencesName)

    @After fun cleanUp() { context.deleteSharedPreferences(preferencesName) }

    @Test fun fast_button_follows_model_and_leaves_quick_inputs_at_right_even_with_large_text() {
        var narrow by mutableStateOf(false)
        var fast by mutableStateOf(false)
        var quickOpens = 0
        compose.setContent {
            val density = LocalDensity.current
            PinkCollabTheme {
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (narrow) 1.5f else 1f)) {
                    Box(Modifier.width(if (narrow) 230.dp else 340.dp).testTag("settingsBar")) {
                        ComposerSettingsBar(
                            ModelInfo("provider", "model", if (narrow) "Very long model name" else "Model",
                                thinkingLevel = "high", fastModeEnabled = fast, fastModeActive = fast),
                            true, {}, { fast = it }, { quickOpens++ },
                        )
                    }
                }
            }
        }
        fun assertLayout() {
            val model = compose.onNode(hasContentDescription("Choose model:", substring = true))
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val fastButton = compose.onNodeWithContentDescription("Fast mode").assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .fetchSemanticsNode().boundsInRoot
            val quick = compose.onNodeWithContentDescription("Quick inputs").assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            val bar = compose.onNodeWithTag("settingsBar").fetchSemanticsNode().boundsInRoot
            assertEquals(model.right, fastButton.left, 1f)
            assertEquals(model.center.y, fastButton.center.y, 1f)
            assertTrue(fastButton.right <= quick.left)
            assertEquals(bar.right, quick.right, 1f)
        }
        assertLayout()
        compose.onNodeWithContentDescription("Fast mode").assertIsOff().performClick().assertIsOn()
        screenshot("fast-pill-enabled.png")
        compose.onNodeWithContentDescription("Fast mode").performClick().assertIsOff()
        screenshot("quick-input-composer-bar.png")
        compose.onNodeWithContentDescription("Quick inputs").performClick()
        compose.runOnIdle { assertEquals(1, quickOpens); narrow = true }
        assertLayout()
    }

    @Test fun snippets_save_edit_insert_reopen_and_delete_on_device() {
        val original = "请检查代码\nKeep whitespace:  x  y"
        val edited = "修改后的快捷输入\nSecond line"
        var open by mutableStateOf(true)
        var inserted: String? = null
        compose.setContent {
            PinkCollabTheme {
                if (open) QuickInputsSheet(store, original, true,
                    onInsert = { inserted = it; open = false }, dismiss = { open = false })
            }
        }
        compose.waitUntil(5_000) { compose.onNodeWithText("Save").isEnabled() }
        compose.onNodeWithTag("quickInputEditor").assertTextContains(original)
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Edit quick input").fetchSemanticsNodes().size == 1 }
        compose.runOnIdle {
            assertEquals(listOf(original), runBlocking { QuickInputStore(context, preferencesName).read() }.map { it.text })
        }
        compose.onNodeWithContentDescription("Edit quick input").performClick()
        compose.onNodeWithTag("quickInputEditor").performTextReplacement(edited)
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { compose.onNodeWithText(edited).isDisplayed() }
        screenshot("quick-input-editor.png")
        compose.onNodeWithText(edited).performClick()
        compose.runOnIdle { assertEquals(edited, inserted); open = true }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Delete quick input").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText(edited).assertIsDisplayed()
        screenshot("quick-input-saved.png")
        compose.onNodeWithContentDescription("Delete quick input").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Delete quick input").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle {
            assertTrue(runBlocking { QuickInputStore(context, preferencesName).read() }.isEmpty())
        }
    }

    @Test fun saved_snippets_remain_manageable_when_session_input_is_disabled() {
        runBlocking { store.save(listOf(QuickInput("one", "Saved prompt"))) }
        compose.setContent {
            PinkCollabTheme {
                QuickInputsSheet(store, "", false, onInsert = { error("Must not insert") }, dismiss = {})
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Edit quick input").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Saved prompt").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Edit quick input").assertIsEnabled()
        compose.onNodeWithContentDescription("Delete quick input").assertIsEnabled()
    }

    private fun SemanticsNodeInteraction.isEnabled(): Boolean =
        !fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
