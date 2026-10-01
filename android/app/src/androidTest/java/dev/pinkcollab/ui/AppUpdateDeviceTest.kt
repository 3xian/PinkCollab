package dev.pinkcollab.ui

import android.view.KeyEvent
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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
import org.junit.Assert.assertTrue
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



    @Test fun update_flow_starts_download_on_update_click() {
        var requested = false
        compose.setContent {
            AppUpdateFlow(AppRelease("v3.0.0", 3_000_000, "", "https://github.com/apk"),
                UpdateDownloadState.Idle, onDismiss = {}, onDownload = { requested = true })
        }
        compose.onNodeWithText("Update").performClick()
        compose.runOnIdle { assertEquals(true, requested) }
    }

    @Test fun completed_download_opens_android_installer_with_readable_apk() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "updates/installer-test.apk")
        file.parentFile!!.mkdirs()
        File(context.applicationInfo.sourceDir).copyTo(file, overwrite = true)
        fun appOp(value: String) {
            instrumentation.uiAutomation.executeShellCommand(
                "appops set ${context.packageName} REQUEST_INSTALL_PACKAGES $value").use {
                java.io.FileInputStream(it.fileDescriptor).readBytes()
            }
        }
        appOp("allow")
        val originalAccessibilityFlags = instrumentation.uiAutomation.serviceInfo.flags
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        try {
            compose.setContent {
                AppUpdateFlow(AppRelease("v3.0.0", 3_000_000, "", "https://github.com/apk"),
                    UpdateDownloadState.Ready(file), onDismiss = {}, onDownload = {})
            }
            // A real APK exercises the content URI grant and Android's package parser.
            try {
                compose.waitUntil(timeoutMillis = 10_000) {
                    val root = instrumentation.uiAutomation.rootInActiveWindow
                    root?.packageName?.toString()?.contains("packageinstaller") == true &&
                        root.findAccessibilityNodeInfosByText(context.applicationInfo.loadLabel(context.packageManager).toString()).isNotEmpty() &&
                        root.findAccessibilityNodeInfosByViewId("android:id/button1").any { it.isEnabled }
                }
            } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                val hierarchy = buildString {
                    fun visit(node: android.view.accessibility.AccessibilityNodeInfo?) {
                        if (node == null) return
                        appendLine("package=${node.packageName} id=${node.viewIdResourceName} text=${node.text} enabled=${node.isEnabled}")
                        for (index in 0 until node.childCount) visit(node.getChild(index))
                    }
                    visit(instrumentation.uiAutomation.rootInActiveWindow)
                }
                throw AssertionError("Installer confirmation was not actionable:\n$hierarchy", failure)
            }
        } finally {
            instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
                flags = originalAccessibilityFlags
            }
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                instrumentation.uiAutomation.injectInputEvent(KeyEvent(action, KeyEvent.KEYCODE_BACK), true)
            }
            // Revoking REQUEST_INSTALL_PACKAGES kills the instrumented process on Android 16.
            // The test runner removes this test installation after the suite.
            file.delete()
        }
    }

    @Test fun download_progress_disables_duplicate_download_and_allows_cancel() {
        var cancelled = false
        compose.setContent {
            AppUpdateDialog(AppRelease("v3.0.0", 3_000_000, "", "https://github.com/apk"), "v2.0.3",
                onDismiss = { cancelled = true }, onUpdate = {},
                download = UpdateDownloadState.Downloading(50, 100))
        }
        compose.onNodeWithText("Downloading: 50%").assertIsDisplayed()
        compose.onNodeWithText("Update").assertIsNotEnabled()
        compose.onNodeWithText("Cancel download").performClick()
        compose.runOnIdle { assertEquals(true, cancelled) }
    }

    @Test fun long_release_notes_keep_progress_visible_while_scrolling() {
        compose.setContent {
            AppUpdateDialog(
                AppRelease("v3.0.0", 3_000_000,
                    (1..100).joinToString("\n") { "Release note $it: improvements and fixes." },
                    "https://github.com/apk"),
                "v2.0.3", onDismiss = {}, onUpdate = {},
                download = UpdateDownloadState.Downloading(50, 100),
            )
        }
        val progress = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        val notes = compose.onNodeWithText("Release note 1:", substring = true)
        val initialRange = notes.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val initialOffset = initialRange.value()
        assertTrue("Long release notes must be scrollable", initialRange.maxValue() > 0f)
        compose.onNodeWithText("Downloading: 50%").assertIsDisplayed()
        progress.assertIsDisplayed()
        notes.performTouchInput { swipeUp() }
        val scrolledRange = notes.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("Swiping must advance the release notes", scrolledRange.value() > initialOffset)
        compose.onNodeWithText("Downloading: 50%").assertIsDisplayed()
        progress.assertIsDisplayed()
        compose.onNodeWithText("Cancel download").assertIsDisplayed()
    }

    @Test fun failed_download_offers_retry_and_explains_error() {
        var retried = false
        compose.setContent {
            AppUpdateDialog(AppRelease("v3.0.0", 3_000_000, "", "https://github.com/apk"), "v2.0.3",
                onDismiss = {}, onUpdate = { retried = true },
                download = UpdateDownloadState.Failed("Download failed (HTTP 503)"))
        }
        compose.onNodeWithText("Download failed (HTTP 503)").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(true, retried) }
    }

    @Test fun release_without_apk_cannot_start_download() {
        compose.setContent {
            AppUpdateDialog(AppRelease("v3.0.0", 3_000_000, "", "https://github.com/release", apkUrl = null),
                "v2.0.3", onDismiss = {}, onUpdate = {})
        }
        compose.onNodeWithText("No APK is available for this release.").assertIsDisplayed()
        compose.onNodeWithText("Update").assertIsNotEnabled()
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
