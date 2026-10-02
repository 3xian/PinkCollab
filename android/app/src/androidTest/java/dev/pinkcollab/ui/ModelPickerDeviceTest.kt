package dev.pinkcollab.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertValueEquals
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelPickerDeviceTest {
    @get:Rule val compose = createComposeRule()

    private val models = (0 until 120).map { index ->
        ModelInfo("provider${index / 30}", "model-$index", "Model $index",
            thinkingLevels = if (index == 0) listOf("off", "low", "high") else listOf("off", "medium", "high"))
    }
    private val catalog = ModelCatalog(models, listOf("off", "low", "high"))

    @Test fun runtime_start_stays_pending_until_snapshot_or_failure_arrives() {
        var starting by mutableStateOf(false)
        var attached by mutableStateOf(false)
        var requests = 0
        var attempt by mutableStateOf<RuntimeStartAttempt?>(null)
        compose.setContent {
            ModelPickerSheet(state = if (attached) LoadState.Ready(catalog) else null,
                current = null, enabled = attached, runtimeAttached = attached,
                runtimeStarting = starting, canStartRuntime = !starting && !attached,
                startRuntime = { requests++; attempt = RuntimeStartAttempt.Pending(null) },
                dismiss = {}, retry = {}, refresh = {}, apply = {}, runtimeStartAttempt = attempt)
        }
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithText("Could not start OMP", substring = true).assertDoesNotExist()
        compose.runOnIdle { starting = true }
        compose.onNodeWithText("Starting OMP…").assertIsDisplayed()
        compose.runOnIdle { starting = false }
        compose.onNodeWithText("Could not start OMP", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Starting OMP…").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.runOnIdle { attached = true }
        compose.onNodeWithTag("modelList").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun failed_runtime_start_keeps_picker_open_and_shows_error() {
        var starting by mutableStateOf(false)
        var attempt by mutableStateOf<RuntimeStartAttempt?>(null)
        compose.setContent {
            ModelPickerSheet(state = null, current = null, enabled = false, runtimeAttached = false,
                runtimeStarting = starting, canStartRuntime = !starting,
                startRuntime = { attempt = RuntimeStartAttempt.Pending(null) },
                dismiss = {}, retry = {}, refresh = {}, apply = {}, runtimeStartAttempt = attempt)
        }
        compose.onNodeWithText("Starting OMP…").assertIsDisplayed()
        compose.runOnIdle { attempt = RuntimeStartAttempt.Failed("previous runtime exit is not confirmed") }
        compose.onNodeWithText("Could not start OMP\nprevious runtime exit is not confirmed").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsEnabled().performClick()
        compose.onNodeWithText("Starting OMP…").assertIsDisplayed()
    }

    @Test fun attached_runtime_failing_during_initialization_keeps_start_error() {
        var starting by mutableStateOf(false)
        var attached by mutableStateOf(false)
        var attempt by mutableStateOf<RuntimeStartAttempt?>(null)
        compose.setContent {
            ModelPickerSheet(state = null, current = null, enabled = false,
                runtimeAttached = attached, runtimeStarting = starting,
                canStartRuntime = !starting,
                startRuntime = { attempt = RuntimeStartAttempt.Pending(null); starting = true },
                dismiss = {}, retry = {}, refresh = {}, apply = {}, runtimeStartAttempt = attempt)
        }
        compose.onNodeWithText("Starting OMP…").assertIsDisplayed()
        compose.runOnIdle { attached = true }
        compose.waitForIdle()
        compose.runOnIdle {
            attached = false
            starting = false
            attempt = RuntimeStartAttempt.Failed("OMP did not load stored session")
        }
        compose.onNodeWithText("Could not start OMP\nOMP did not load stored session").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsEnabled().performClick()
        compose.onNodeWithText("Starting OMP…").assertIsDisplayed()
    }

    @Test fun unchanged_settings_can_close_without_applying_and_current_survives_draft_selection() {
        var applied: ModelSettingsChanges? = null
        var dismissed = 0
        compose.setContent {
            ModelPickerSheet(
                state = LoadState.Ready(catalog), current = models.first().copy(thinkingLevel = "high"),
                enabled = true, runtimeAttached = true, runtimeStarting = false,
                canStartRuntime = false, startRuntime = {}, dismiss = { dismissed++ }, retry = {}, refresh = {},
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

    @Test fun single_thinking_level_can_be_selected_and_applied_when_unset() {
        var applied: ModelSettingsChanges? = null
        compose.setContent {
            ModelPickerSheet(
                state = LoadState.Ready(ModelCatalog(emptyList(), listOf("high"))),
                current = ModelInfo("provider", "model", "Model"),
                enabled = true, runtimeAttached = true, runtimeStarting = false,
                canStartRuntime = false, startRuntime = {}, dismiss = {}, retry = {}, refresh = {},
                apply = { applied = it },
            )
        }
        compose.onNodeWithText("Thinking Not set").assertIsDisplayed()
        compose.onNodeWithTag("thinkingSingleLevel").performClick()
        compose.onNodeWithText("Apply").performClick()
        assertEquals(ModelSettingsChanges(null, "high"), applied)
    }

    @Test fun first_thinking_level_can_be_selected_accessibly_and_applied_when_unset() {
        var applied: ModelSettingsChanges? = null
        compose.setContent {
            ModelPickerSheet(
                state = LoadState.Ready(catalog),
                current = models.first(),
                enabled = true, runtimeAttached = true, runtimeStarting = false,
                canStartRuntime = false, startRuntime = {}, dismiss = {}, retry = {}, refresh = {},
                apply = { applied = it },
            )
        }
        compose.onNodeWithText("Thinking Not set").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsEnabled()
        compose.onNodeWithTag("thinkingLevel:off")
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("thinkingSlider").assertValueEquals("off")
        compose.onNodeWithText("Apply").performClick()
        assertEquals(ModelSettingsChanges(null, "off"), applied)
    }

    @Test fun model_switch_with_unsupported_thinking_allows_selecting_first_level() {
        var applied: ModelSettingsChanges? = null
        compose.setContent {
            ModelPickerSheet(
                state = LoadState.Ready(catalog),
                current = models.first().copy(thinkingLevel = "low"),
                enabled = true, runtimeAttached = true, runtimeStarting = false,
                canStartRuntime = false, startRuntime = {}, dismiss = {}, retry = {}, refresh = {},
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
        assertEquals(ModelSettingsChanges(models[1], "off"), applied)
    }

    @Test fun supported_selection_keeps_slider_level_mapping() {
        var applied: ModelSettingsChanges? = null
        compose.setContent {
            ModelPickerSheet(
                state = LoadState.Ready(catalog),
                current = models.first().copy(thinkingLevel = "low"),
                enabled = true, runtimeAttached = true, runtimeStarting = false,
                canStartRuntime = false, startRuntime = {}, dismiss = {}, retry = {}, refresh = {},
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
        assertEquals(ModelSettingsChanges(null, "off"), applied)
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
                runtimeAttached = true,
                runtimeStarting = false,
                canStartRuntime = false,
                startRuntime = {},
                dismiss = { dismissed++ },
                retry = {},
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
        assertEquals(ModelSettingsChanges(models[113], "high"), applied)
    }

    @Test fun thinking_remains_available_with_empty_catalog() {
        var applied: ModelSettingsChanges? = null
        compose.setContent {
            ModelPickerSheet(
                state = LoadState.Ready(ModelCatalog(emptyList(), listOf("low", "high"))),
                current = ModelInfo("provider", "old", "Old", "low"),
                enabled = true,
                runtimeAttached = true,
                runtimeStarting = false,
                canStartRuntime = false,
                startRuntime = {},
                dismiss = {},
                retry = {},
                refresh = {},
                apply = { applied = it },
            )
        }
        compose.onNodeWithText("Thinking ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("No models are available from OMP.").assertExists()
        compose.onNodeWithTag("thinkingSlider").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithText("Apply").performClick()
        assertEquals(ModelSettingsChanges(null, "high"), applied)
    }
}
