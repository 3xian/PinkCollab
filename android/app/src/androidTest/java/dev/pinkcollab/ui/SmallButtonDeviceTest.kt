package dev.pinkcollab.ui

import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pinkcollab.data.ModelCatalog
import dev.pinkcollab.data.ModelInfo
import dev.pinkcollab.ui.theme.PinkCollabTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmallButtonDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun apply_row_is_small() {
        compose.setContent {
            PinkCollabTheme {
                ModelPickerSheet(
                    state = LoadState.Ready(ModelCatalog(emptyList(), emptyList())),
                    current = null,
                    enabled = false,
                    runtimeAttached = true,
                    runtimeStarting = false,
                    canStartRuntime = false,
                    startRuntime = {},
                    dismiss = {},
                    retry = {},
                    refresh = {},
                    apply = {},
                )
            }
        }
        compose.onNodeWithText("Cancel").assertIsDisplayed().assertHeightIsEqualTo(40.dp)
        compose.onNodeWithText("Apply").assertIsDisplayed().assertHeightIsEqualTo(40.dp)
    }

    @Test fun pair_button_is_small() {
        compose.setContent {
            PinkCollabTheme {
                PairHostModal(
                    state = PairHostSheetState(visible = true, url = "https://host", token = "token"),
                    onStateChange = {},
                    onPair = { _, _ -> },
                )
            }
        }
        compose.onNodeWithText("Pair").assertIsDisplayed().assertHeightIsEqualTo(40.dp)
    }
}
