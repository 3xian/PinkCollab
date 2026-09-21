package dev.pinkcollab.ui

import dev.pinkcollab.data.ModelInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSelectionTest {
    @Test
    fun duplicate_model_targets_are_selected_by_role_and_thinking_level() {
        val low = ModelInfo("fixture", "smart", "Smart", "smart", "low")
        val high = ModelInfo("fixture", "smart", "Smart", "deep", "high")

        assertFalse(isSelectedModel(high, low))
        assertTrue(isSelectedModel(high, high))
    }

    @Test
    fun externally_selected_models_fall_back_to_provider_id_and_thinking_level() {
        val current = ModelInfo("fixture", "smart", "Smart", thinkingLevel = "high")
        val candidate = ModelInfo("fixture", "smart", "Smart", "deep", "high")

        assertTrue(isSelectedModel(current, candidate))
    }
}
