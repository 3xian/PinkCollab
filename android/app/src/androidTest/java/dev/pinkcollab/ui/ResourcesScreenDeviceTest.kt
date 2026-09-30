package dev.pinkcollab.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pinkcollab.data.AppState
import dev.pinkcollab.data.Host
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.PairedHost
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResourcesScreenDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun host_url_stays_masked_until_its_eye_is_tapped() {
        val first = "https://alpha.example"
        val second = "https://beta.example/longer"
        compose.setContent {
            ResourcesScreen(
                navigationState(AppState(hosts = mapOf(
                    "a" to host("a", "Alpha", first),
                    "b" to host("b", "Beta", second),
                ))),
                hostBusy = { false },
                browse = { _, _ -> },
                pair = {},
                refresh = {},
                forget = {},
            )
        }

        compose.onNodeWithText(first).assertDoesNotExist()
        compose.onNodeWithText(second).assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Show URL").assertCountEquals(2)

        compose.onAllNodesWithContentDescription("Show URL")[0].performClick()
        val firstShown = compose.onAllNodesWithText(first).fetchSemanticsNodes().isNotEmpty()
        val secondShown = compose.onAllNodesWithText(second).fetchSemanticsNodes().isNotEmpty()
        assertTrue(firstShown != secondShown)
        compose.onNodeWithText(if (firstShown) first else second).assertIsDisplayed()
        compose.onNodeWithText(if (firstShown) second else first).assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Hide URL").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Show URL").assertCountEquals(1)

        compose.onNodeWithContentDescription("Hide URL").performClick()
        compose.onNodeWithText(first).assertDoesNotExist()
        compose.onNodeWithText(second).assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Show URL").assertCountEquals(2)
    }

    private fun host(id: String, name: String, url: String) = HostState(
        PairedHost(Host(id, name, "macOS", "1.0", "0.2"), url, "credential", "client"),
    )
}
