package dev.pinkcollab.ui

import dev.pinkcollab.data.ModelInfo
import dev.pinkcollab.data.ModelCatalog
import dev.pinkcollab.data.modelInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSelectionTest {
    private val models = listOf(
        ModelInfo("openai", "gpt-5.6-sol", "GPT-5.6 Sol"),
        ModelInfo("anthropic", "claude-sonnet", "Claude Sonnet"),
        ModelInfo("openai", "codex", "GPT Codex"),
    )

    @Test
    fun model_selection_uses_provider_and_id() {
        val low = ModelInfo("fixture", "smart", "Smart", "low")
        val high = ModelInfo("fixture", "smart", "Smart", "high")

        assertTrue(isSelectedModel(high, low))
        assertTrue(isSelectedModel(high, high))
        assertFalse(isSelectedModel(ModelInfo("other", "smart", "Smart"), low))
        assertFalse(isSelectedModel(ModelInfo("fixture", "other", "Smart"), low))
    }

    @Test
    fun externally_selected_models_fall_back_to_provider_id_and_thinking_level() {
        val current = ModelInfo("fixture", "smart", "Smart", thinkingLevel = "high")
        val candidate = ModelInfo("fixture", "smart", "Smart", "high")

        assertTrue(isSelectedModel(current, candidate))
    }

    @Test
    fun composer_model_label_is_the_selected_name() {
        assertEquals("Claude Sonnet", composerModelLabel(ModelInfo("anthropic", "claude-sonnet", "Claude Sonnet")))
        assertEquals("claude-sonnet", composerModelLabel(ModelInfo("anthropic", "claude-sonnet", " ")))
    }

    @Test
    fun composer_thinking_label_is_separate_from_the_model_name() {
        val model = ModelInfo("anthropic", "claude-sonnet", "Claude Sonnet", "high")
        assertEquals("Claude Sonnet", composerModelLabel(model))
        assertEquals("high", composerThinkingLabel(model))
        assertNull(composerThinkingLabel(null))
        assertNull(composerThinkingLabel(model.copy(thinkingLevel = " ")))
    }

    @Test fun search_matches_name_provider_and_id_ignoring_case() {
        assertEquals(listOf(models[1]), filterModels(models, "SONNET"))
        assertEquals(listOf(models[1]), filterModels(models, "ANTHROPIC"))
        assertEquals(listOf(models[0]), filterModels(models, "5.6-SOL"))
        assertEquals(listOf(models[2]), filterModels(models, "CoDeX"))
        assertTrue(filterModels(models, "missing").isEmpty())
        assertEquals(models, filterModels(models, "  "))
    }

    @Test fun grouping_preserves_first_provider_and_model_order() {
        val grouped = groupModelsByProvider(models)
        assertEquals(listOf("openai", "anthropic"), grouped.keys.toList())
        assertEquals(listOf(models[0], models[2]), grouped["openai"])
        assertEquals(listOf(models[1]), grouped["anthropic"])
        assertEquals(listOf("anthropic"), groupModelsByProvider(filterModels(models, "sonnet")).keys.toList())
    }

    @Test fun pending_changes_do_not_mutate_current_and_cancel_has_no_changes_to_submit() {
        val current = models[0].copy(thinkingLevel = "low")
        val opened = ModelSettingsDraft.from(current)
        val edited = opened.copy(model = models[1], thinkingLevel = "high")

        assertEquals(current, opened.model)
        assertEquals("low", opened.thinkingLevel)
        assertEquals("low", current.thinkingLevel)
        assertTrue(modelSettingsChanges(current, opened).isEmpty)
        assertEquals(ModelSettingsChanges(models[1], "high"), modelSettingsChanges(current, edited))
        // Dismissing discards the draft; opening again reads the latest actual state.
        assertEquals(opened, ModelSettingsDraft.from(current))
    }

    @Test fun change_plan_only_includes_changed_fields() {
        val current = models[0].copy(thinkingLevel = "low")
        assertEquals(ModelSettingsChanges(models[1], null),
            modelSettingsChanges(current, ModelSettingsDraft(models[1], "low")))
        assertEquals(ModelSettingsChanges(null, "high"),
            modelSettingsChanges(current, ModelSettingsDraft(models[0], "high")))
        assertTrue(modelSettingsChanges(current, ModelSettingsDraft(current.copy(thinkingLevel = "high"), "low")).isEmpty)
    }

    @Test fun thinking_levels_follow_the_pending_model_and_keep_wire_values() {
        val current = ModelInfo("provider", "full", "Full", "xhigh", listOf("off", "low", "xhigh"))
        val medModel = ModelInfo("provider", "med", "Med", thinkingLevels = listOf("off", "med", "high"))
        val catalog = ModelCatalog(listOf(current, medModel), listOf("off", "low", "xhigh"))

        assertEquals(listOf("off", "med", "high"), thinkingLevelsForModel(catalog, medModel, current))
        val changed = selectModelDraft(catalog, current, ModelSettingsDraft.from(current), medModel)
        assertNull(changed.thinkingLevel)
        assertEquals(ModelSettingsChanges(medModel, null), modelSettingsChanges(current, changed))
        assertEquals(ModelSettingsChanges(medModel, "med"),
            modelSettingsChanges(current, changed.copy(thinkingLevel = "med")))
        assertEquals(listOf("off", "low", "xhigh"), thinkingLevelsForModel(catalog, current, current))
    }

    @Test fun model_parser_reads_per_model_thinking_levels() {
        val parsed = JSONObject("""{"provider":"fixture","id":"med","name":"Med","thinkingLevels":["off","med","high"]}""").modelInfo()
        assertEquals(listOf("off", "med", "high"), parsed.thinkingLevels)
        val oldGateway = JSONObject("""{"provider":"fixture","id":"old","name":"Old"}""").modelInfo()
        assertNull(oldGateway.thinkingLevels)
    }

    @Test fun only_the_current_models_provider_starts_expanded() {
        val catalog = ModelCatalog(models, emptyList())
        assertEquals(setOf("anthropic"), initiallyExpandedProviders(catalog, models[1]))
        assertTrue(initiallyExpandedProviders(catalog, ModelInfo("missing", "id", "Missing")).isEmpty())
        assertTrue(initiallyExpandedProviders(catalog, null).isEmpty())
    }
}
