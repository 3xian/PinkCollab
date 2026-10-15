package dev.pinkcollab.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoadingIndicatorsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun indicators_respect_caller_constraints_and_expose_progress() {
        compose.setContent {
            Column {
                OrbitDots(Modifier.size(18.dp).testTag("orbit"))
                MorphIndicator(Modifier.testTag("morph"))
                MorphIndicator(Modifier.size(24.dp).testTag("smallMorph"))
            }
        }
        listOf("orbit" to 18.dp, "morph" to 48.dp, "smallMorph" to 24.dp).forEach { (tag, size) ->
            compose.onNodeWithTag(tag)
                .assertWidthIsEqualTo(size)
                .assertHeightIsEqualTo(size)
                .assert(SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
                ))
        }
    }
}
