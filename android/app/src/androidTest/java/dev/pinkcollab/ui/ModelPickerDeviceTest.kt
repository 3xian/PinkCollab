package dev.pinkcollab.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertValueEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pinkcollab.data.ModelCatalog
import dev.pinkcollab.data.ModelInfo
import dev.pinkcollab.ui.theme.PinkCollabTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelPickerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val models = (0 until 120).map { index ->
        ModelInfo("provider${index / 30}", "model-$index", "Model $index",
            thinkingLevels = if (index == 0) listOf("off", "low", "high") else listOf("off", "medium", "high"))
    }
    private val catalog = ModelCatalog(models, listOf("off", "low", "high"))

    @Test fun narrow_large_text_keeps_header_unclipped_and_actions_reachable() {
        compose.setContent {
            val density = LocalDensity.current
            PinkCollabTheme {
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                    Box(Modifier.width(320.dp)) {
                        ModelPickerHeader({}, {}, canUseHost = true, canRefresh = true)
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        val heading = compose.onNodeWithText("Models").assertIsDisplayed()
        heading.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("Model heading is clipped", layouts.single().hasVisualOverflow)
        val headingBounds = heading.fetchSemanticsNode().boundsInRoot
        for (label in listOf("Reload", "Usage")) {
            val action = compose.onNodeWithText(label).assertIsDisplayed()
            assertFalse("Model heading overlaps $label", headingBounds.overlaps(action.fetchSemanticsNode().boundsInRoot))
        }
    }

    @Test fun recreating_during_apply_and_after_conflict_preserves_the_wait_and_retryable_draft() {
        val release = CompletableDeferred<Unit>()
        val busy = "This session is open on your computer. Close it there, then tap Apply."
        var current by mutableStateOf<ModelInfo?>(null)
        var dismissed = 0
        val attempts = mutableListOf<ModelSettingsChanges>()
        val picker: @Composable () -> Unit = {
            ModelPickerSheet(state = LoadState.Ready(catalog), current = current, enabled = true,
                dismiss = { dismissed++ }, refresh = {}, apply = {
                    attempts += it
                    if (attempts.size == 1) {
                        release.await()
                        throw IllegalStateException(busy)
                    }
                })
        }
        val scenario = compose.activityRule.scenario
        fun mount() { scenario.onActivity { it.setContent(content = picker) } }
        mount()
        compose.onNodeWithContentDescription("Search models").performTextInput("Model 113")
        val row = compose.onNodeWithContentDescription("Model 113, provider3")
        row.assertIsNotSelected().performClick().assertIsSelected()
        compose.onNodeWithText("Current", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("thinkingLevel:high").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(emptyList<ModelSettingsChanges>(), attempts) }
        compose.onNodeWithText("Apply").performClick()
        compose.onNodeWithText("Applying model settings…").assertIsDisplayed()
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        scenario.recreate()
        mount()
        compose.onNodeWithText("Applying model settings…").assertIsDisplayed()
        row.assertIsSelected()
        compose.runOnIdle { assertEquals(1, attempts.size) }
        compose.runOnIdle { assertEquals(0, dismissed); release.complete(Unit) }
        compose.onNodeWithText(busy).assertIsDisplayed()
        scenario.recreate()
        mount()
        compose.onNodeWithText(busy).assertIsDisplayed()
        // A new attached snapshot is not a retry. The search and edits still belong to the user.
        compose.runOnIdle { current = models.first() }
        row.assertIsSelected()
        compose.onNodeWithContentDescription("Model 0, provider0").assertDoesNotExist()
        compose.onNodeWithTag("thinkingSlider").assertValueEquals("high")
        val expected = ModelSettingsChanges(models[113], "high")
        compose.runOnIdle { assertEquals(listOf(expected), attempts); assertEquals(0, dismissed) }
        compose.onNodeWithText("Apply").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(expected, expected), attempts); assertEquals(1, dismissed) }
    }

    @Test fun catalog_replacement_blocks_missing_draft_until_a_supported_replacement_is_selected() {
        val original = models[1]
        val replacement = original.copy(id = "replacement", name = "Model 1 replacement")
        var catalog by mutableStateOf(ModelCatalog(listOf(original), emptyList()))
        val attempts = mutableListOf<ModelSettingsChanges>()
        compose.setContent {
            ModelPickerSheet(LoadState.Ready(catalog), current = null, enabled = true,
                dismiss = {}, refresh = {}, apply = { attempts += it })
        }
        compose.onNodeWithContentDescription("Search models").performTextInput("Model 1")
        compose.onNodeWithContentDescription("Model 1, provider0").performClick()
        compose.onNodeWithTag("thinkingLevel:high").performClick()
        compose.runOnIdle { catalog = ModelCatalog(listOf(replacement), emptyList()) }
        compose.onNodeWithText("Model 1 is no longer available.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Model 1 replacement, provider0").performClick()
        compose.onNodeWithText("Apply").assertIsEnabled()

        val reduced = replacement.copy(thinkingLevels = listOf("low"))
        compose.runOnIdle { catalog = ModelCatalog(listOf(reduced), emptyList()) }
        compose.onNodeWithText("Thinking level high is no longer available.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(emptyList<ModelSettingsChanges>(), attempts) }
        compose.onNodeWithTag("thinkingSingleLevel").performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertEquals(listOf(ModelSettingsChanges(reduced, "low")), attempts) }
    }

    @Test fun closing_during_apply_stops_waiting_and_reopening_has_no_stale_draft() {
        var open by mutableStateOf(true)
        val ready = CompletableDeferred<Unit>()
        var completed = 0
        var cancelled = 0
        compose.setContent {
            if (open) ModelPickerSheet(state = LoadState.Ready(catalog), current = null, enabled = true,
                dismiss = { open = false }, refresh = {}, apply = {
                    try {
                        ready.await()
                        completed++
                    } finally {
                        cancelled++
                    }
                })
        }
        compose.onNodeWithTag("provider:provider0").performClick()
        compose.onNodeWithContentDescription("Model 0, provider0").performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.onNodeWithText("Applying model settings…").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Models").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, cancelled); open = true; ready.complete(Unit) }
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, completed) }
    }

    @Test fun unchanged_settings_can_close_without_applying_and_current_survives_draft_selection() {
        var applied: ModelSettingsChanges? = null
        var dismissed = 0
        compose.setContent {
            ModelPickerSheet(
                state = LoadState.Ready(catalog), current = models.first().copy(thinkingLevel = "high"),
                enabled = true, dismiss = { dismissed++ }, refresh = {},
                apply = { applied = it },
            )
        }
        compose.onNodeWithText("Done").assertIsEnabled()
        compose.onNodeWithText("Current", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Model 1, provider0").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Model 0, provider0").assertIsNotSelected()
        compose.onNodeWithText("Current", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Apply").assertIsEnabled()
        compose.onNodeWithContentDescription("Model 0, provider0").performClick()
        compose.onNodeWithText("Apply").assertDoesNotExist()
        compose.onNodeWithText("Done").performClick()
        assertEquals(1, dismissed)
        assertNull(applied)
    }

    @Test fun attached_thinking_only_change_can_be_applied_without_a_current_model_descriptor() {
        var applied: ModelSettingsChanges? = null
        compose.setContent {
            ModelPickerSheet(
                state = LoadState.Ready(ModelCatalog(emptyList(), listOf("high"))),
                current = null,
                enabled = true, dismiss = {}, refresh = {},
                apply = { applied = it },
            )
        }
        compose.onNodeWithText("Thinking Not set").assertIsDisplayed()
        compose.onNodeWithTag("thinkingSingleLevel").performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertEquals(ModelSettingsChanges(null, "high"), applied) }
    }

    @Test fun first_thinking_level_can_be_selected_accessibly_and_applied_when_unset() {
        var applied: ModelSettingsChanges? = null
        compose.setContent {
            ModelPickerSheet(
                state = LoadState.Ready(catalog),
                current = models.first(),
                enabled = true, dismiss = {}, refresh = {},
                apply = { applied = it },
            )
        }
        compose.onNodeWithText("Thinking Not set").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsEnabled()
        compose.onNodeWithTag("thinkingLevel:off")
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("thinkingSlider").assertValueEquals("off")
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertEquals(ModelSettingsChanges(null, "off"), applied) }
    }

    @Test fun model_switch_with_unsupported_thinking_allows_selecting_first_level() {
        var applied: ModelSettingsChanges? = null
        compose.setContent {
            ModelPickerSheet(
                state = LoadState.Ready(catalog),
                current = models.first().copy(thinkingLevel = "low"),
                enabled = true, dismiss = {}, refresh = {},
                apply = { applied = it },
            )
        }
        compose.onNodeWithTag("thinkingSlider").assertValueEquals("low")
        compose.onNodeWithContentDescription("Model 1, provider0").performClick()
        compose.onNodeWithText("Thinking Not set").assertIsDisplayed()
        compose.onNodeWithTag("thinkingLevel:off")
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("thinkingSlider").assertValueEquals("off")
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertEquals(ModelSettingsChanges(models[1], "off"), applied) }
    }

    @Test fun supported_selection_keeps_slider_level_mapping() {
        var applied: ModelSettingsChanges? = null
        compose.setContent {
            ModelPickerSheet(
                state = LoadState.Ready(catalog),
                current = models.first().copy(thinkingLevel = "low"),
                enabled = true, dismiss = {}, refresh = {},
                apply = { applied = it },
            )
        }
        val slider = compose.onNodeWithTag("thinkingSlider")
        slider.assertValueEquals("low")
        compose.onNodeWithText("Done").assertIsEnabled()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        slider.assertValueEquals("off")
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        slider.assertValueEquals("high")
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        slider.assertValueEquals("low")
        compose.onNodeWithText("Done").assertIsEnabled()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertEquals(ModelSettingsChanges(null, "off"), applied) }
    }

    @Test fun large_catalog_scroll_search_and_pending_apply() {
        var applied: ModelSettingsChanges? = null
        var dismissed = 0
        var refreshes = 0
        var load by mutableStateOf<LoadState<ModelCatalog>>(LoadState.Ready(catalog))
        compose.setContent {
            ModelPickerSheet(
                state = load,
                current = models.first().copy(thinkingLevel = "low"),
                enabled = true,
                dismiss = { dismissed++ },
                refresh = {
                    refreshes++
                    load = LoadState.Ready(catalog, refreshing = true)
                },
                apply = { applied = it },
            )
        }

        compose.onNodeWithTag("modelList").performTouchInput { swipeUp() }
        compose.onNodeWithText("Models").assertIsDisplayed()
        compose.onNodeWithText("Thinking ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
        assertEquals(0, dismissed)

        compose.onNodeWithTag("thinkingSlider").performTouchInput {
            swipe(Offset(width * 0.15f, height / 2f), Offset(width * 0.9f, height / 2f), 450)
        }
        compose.onNodeWithTag("thinkingSlider").assertValueEquals("high")

        compose.onNodeWithTag("modelList").performScrollToNode(hasTestTag("provider:provider3"))
        compose.onNodeWithTag("provider:provider3").performClick()
        compose.onNodeWithTag("modelList").performScrollToNode(
            androidx.compose.ui.test.hasContentDescription("Model 119, provider3"),
        )
        compose.onNodeWithContentDescription("Model 119, provider3").performClick()
        assertNull(applied)

        compose.onNodeWithText("Reload").performClick()
        assertEquals(1, refreshes)
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        compose.onNodeWithText("Refreshing models…").assertIsDisplayed()
        compose.onNodeWithText("Thinking ", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("modelList").assertExists()
        compose.runOnIdle { load = LoadState.Ready(catalog) }

        compose.onNodeWithContentDescription("Search models").performTextInput("Model 113")
        compose.onNodeWithContentDescription("Model 113, provider3").assertExists()
        compose.onNodeWithContentDescription("Model 119, provider3").assertDoesNotExist()
        compose.onNodeWithContentDescription("Model 113, provider3").performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertEquals(ModelSettingsChanges(models[113], "high"), applied) }
    }

    @Test fun thinking_remains_available_with_empty_catalog() {
        var applied: ModelSettingsChanges? = null
        compose.setContent {
            ModelPickerSheet(
                state = LoadState.Ready(ModelCatalog(emptyList(), listOf("low", "high"))),
                current = ModelInfo("provider", "old", "Old", "low"),
                enabled = true,
                dismiss = {},
                refresh = {},
                apply = { applied = it },
            )
        }
        compose.onNodeWithText("Thinking ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("No models are available from OMP.").assertExists()
        compose.onNodeWithTag("thinkingSlider").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertEquals(ModelSettingsChanges(null, "high"), applied) }
    }
}
