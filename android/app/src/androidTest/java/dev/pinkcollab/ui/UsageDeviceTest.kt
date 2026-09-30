package dev.pinkcollab.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pinkcollab.data.*
import dev.pinkcollab.ui.theme.PinkCollabTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsageDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun usage_opens_without_runtime_and_returns_to_models() {
        var loads = 0
        val account = UsageAccount("account", "openai-codex", "de***", "Pro", 1790780994681,
            "available", listOf(UsageLimit("weekly", "7 days", null, null, "7 days",
                1791351322000, 0.62, 62.0, 100.0, 38.0, "percent", "ok")))
        compose.setContent { PinkCollabTheme {
            ModelPickerSheet(state = null, current = null, enabled = false, runtimeAttached = false,
                runtimeStarting = false, canStartRuntime = false, startRuntime = {}, dismiss = {},
                retry = {}, refresh = {}, apply = {}, usageState = LoadState.Ready(UsageSnapshot(1790781273269, listOf(account))),
                loadUsage = { loads++ })
        } }
        compose.onNodeWithText("Reload").assertIsDisplayed()
        pauseForInspection()
        compose.onNodeWithContentDescription("Close model settings").assertDoesNotExist()
        compose.onNodeWithText("Usage").performClick()
        compose.onNodeWithText("62.0% used").assertIsDisplayed()
        pauseForInspection()
        assertEquals(1, loads)
        compose.onNodeWithContentDescription("Back to models").performClick()
        compose.onNodeWithText("Models").assertIsDisplayed()
    }
    private fun pauseForInspection() {
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("inspectUsage") == "true") {
            Thread.sleep(15_000)
        }
    }
    @Test fun failed_usage_has_retry() {
        var retries = 0
        compose.setContent { PinkCollabTheme {
            UsageSheet(LoadState.Failed("Usage unavailable"), { retries++ }, {})
        } }
        compose.onNodeWithText("Usage unavailable").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }
}
