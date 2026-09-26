package dev.pinkcollab.ui

import dev.pinkcollab.data.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSelectionTest {
    @Test
    fun model_selection_uses_provider_and_id() {
        val low = ModelInfo("fixture", "smart", "Smart", "low")
        val high = ModelInfo("fixture", "smart", "Smart", "high")

        assertTrue(isSelectedModel(high, low))
        assertTrue(isSelectedModel(high, high))
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
        assertEquals("Not selected", composerModelLabel(null))
        assertEquals("Not selected", composerModelLabel(ModelInfo("anthropic", "", " ")))
    }

    @Test
    fun composer_thinking_label_is_separate_from_the_model_name() {
        val model = ModelInfo("anthropic", "claude-sonnet", "Claude Sonnet", "high")
        assertEquals("Claude Sonnet", composerModelLabel(model))
        assertEquals("high", composerThinkingLabel(model))
        assertNull(composerThinkingLabel(null))
        assertNull(composerThinkingLabel(model.copy(thinkingLevel = " ")))
    }
}
