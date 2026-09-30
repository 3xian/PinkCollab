package dev.pinkcollab.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pinkcollab.BuildConfig
import dev.pinkcollab.data.AppRelease
import dev.pinkcollab.data.AppState
import dev.pinkcollab.data.Host
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.InitialSyncState
import dev.pinkcollab.data.PairedHost
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdateDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun paired_host_actions_fit_at_280dp() {
        assertPairedHostActionsAvailable(280.dp, 1f)
    }

    @Test fun paired_host_actions_fit_at_320dp_with_large_text() {
        assertPairedHostActionsAvailable(320.dp, 1.3f)
    }

    private fun assertPairedHostActionsAvailable(width: Dp, fontScale: Float) {
        val host = HostState(
            PairedHost(Host("host", "Desktop", "", "", ""), "https://host", "credential", "client"),
            initialSync = InitialSyncState.Ready,
        )
        var openedWorkspaces = 0
        var checkedUpdates = 0
        var shownVersion = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Box(Modifier.width(width)) {
                    TasksScreen(
                        TasksScreenState(sessionListState(AppState(hosts = mapOf("host" to host))), emptyMap(), emptyMap(), emptyMap(),
                            emptySet(), emptyMap(), emptyMap(), emptyMap(), null),
                        TasksScreenActions({}, { openedWorkspaces++ }, {}, { checkedUpdates++ },
                            { shownVersion++ }, { _, _ -> }, { _, _ -> true }),
                    )
                }
            }
        }
        compose.onNodeWithText("Workspaces").assertIsDisplayed().performClick()
        compose.onNodeWithText("Conf").assertIsDisplayed().performClick()
        compose.onNodeWithText("Current version: v${BuildConfig.VERSION_NAME}")
            .assertIsDisplayed().performClick()
        compose.onNodeWithText("Conf").performClick()
        compose.onNodeWithText("Check for updates").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, openedWorkspaces)
            assertEquals(1, shownVersion)
            assertEquals(1, checkedUpdates)
        }
    }

    @Test fun update_dialog_shows_versions_notes_and_actions() {
        compose.setContent {
            var release by remember {
                mutableStateOf<AppRelease?>(AppRelease("v0.3.0", 3_000, "Important fixes", "https://github.com/apk"))
            }
            release?.let {
                AppUpdateDialog(it, "v${BuildConfig.VERSION_NAME}",
                    onDismiss = { release = null }, onUpdate = { release = null })
            }
        }
        compose.onNodeWithText("Current version: v${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        compose.onNodeWithText("Latest version: v0.3.0").assertIsDisplayed()
        compose.onNodeWithText("Important fixes").assertIsDisplayed()
        compose.onNodeWithText("Update").assertIsDisplayed()
        compose.onNodeWithText("Later").performClick()
        compose.onNodeWithText("Update").assertDoesNotExist()
    }
}
