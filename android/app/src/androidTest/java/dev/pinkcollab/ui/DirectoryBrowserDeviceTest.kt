package dev.pinkcollab.ui

import android.graphics.Color
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pinkcollab.data.AppState
import dev.pinkcollab.data.Listing
import dev.pinkcollab.data.Workspace
import dev.pinkcollab.ui.theme.PinkCollabTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectoryBrowserDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun create_button_keeps_its_gap_and_accepts_touch_through_keyboard_transitions() {
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            )
            it.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        lateinit var view: View
        val selected = mutableListOf<String>()
        compose.setContent {
            view = LocalView.current
            PinkCollabTheme {
                AppScaffold(
                    title = "Host",
                    hasParentDirectory = true, onBack = {},
                ) {
                    DirectoryBrowserScreen(
                        state = LoadState.Ready(Listing("/projects", "/", List(8) {
                            Workspace("project-$it", "/projects/project-$it")
                        })),
                        hostOs = "linux", routePath = "/projects", roots = listOf("/projects"),
                        parentPath = "/", knownPaths = emptyList(), creating = false,
                        browse = {}, select = { selected.add(it) }, refresh = {},
                    )
                }
            }
        }
        val button = compose.onNodeWithText("Create session in", substring = true)
        fun verifyGapAndTouch(imeVisible: Boolean) {
            // Wait for both platform insets and Compose's animated layout to settle.
            compose.waitUntil(timeoutMillis = 5_000) {
                val bounds = button.fetchSemanticsNode().boundsInWindow
                var settled = false
                compose.runOnIdle {
                    val insets = requireNotNull(ViewCompat.getRootWindowInsets(view))
                    val bottom = insets.getInsets(
                        WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime(),
                    ).bottom
                    val expected = view.rootView.height - bottom - 12f * view.resources.displayMetrics.density
                    settled = insets.isVisible(WindowInsetsCompat.Type.ime()) == imeVisible &&
                        abs(bounds.bottom - expected) <= 1f
                }
                settled
            }
            button.assertIsDisplayed()
            val bounds = button.fetchSemanticsNode().boundsInWindow
            compose.runOnIdle {
                val insets = requireNotNull(ViewCompat.getRootWindowInsets(view))
                assertTrue("Exercise a visible navigation bar",
                    insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom > 0)
                assertEquals(imeVisible, insets.isVisible(WindowInsetsCompat.Type.ime()))
                val bottom = insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime(),
                ).bottom
                assertEquals("Keep exactly 12dp above the navigation bar or keyboard",
                    view.rootView.height - bottom - 12f * view.resources.displayMetrics.density,
                    bounds.bottom, 1f)
            }
            // Touch the lower edge, where overlapping system UI would intercept input.
            button.performTouchInput { click(Offset(center.x, bounds.height - 2f)) }
        }

        verifyGapAndTouch(imeVisible = false)
        compose.onNode(hasSetTextAction()).performTouchInput { click() }
        verifyGapAndTouch(imeVisible = true)
        compose.onNode(hasSetTextAction()).performImeAction()
        verifyGapAndTouch(imeVisible = false)
        compose.runOnIdle { assertEquals(List(3) { "/projects" }, selected) }
    }

    @Test fun home_header_stays_below_status_bar_after_browsing_and_returning() {
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            )
        }
        lateinit var view: View
        var route by mutableStateOf<AppRoute>(AppRoute.Tasks)
        compose.setContent {
            view = LocalView.current
            PinkCollabTheme {
                AppScaffold(
                    title = if (route == AppRoute.Tasks) null else "Workspaces",
                    hasParentDirectory = false,
                    onBack = { route = route.back() },
                ) {
                    when (route) {
                        AppRoute.Tasks -> TasksScreen(
                            TasksScreenState(
                                sessionListState(AppState()), emptyMap(), emptyMap(), emptyMap(),
                                emptySet(), emptyMap(), emptyMap(), emptyMap(), null,
                            ),
                            TasksScreenActions({}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> true }),
                        )
                        AppRoute.Resources -> Text("Workspace directory")
                        is AppRoute.Browser -> DirectoryBrowserScreen(
                            state = LoadState.Ready(Listing("/projects", "/", emptyList())),
                            hostOs = "linux", routePath = "/projects", roots = listOf("/projects"),
                            parentPath = "/", knownPaths = emptyList(), creating = false,
                            browse = {}, select = {}, refresh = {},
                        )
                    }
                }
            }
        }
        val header = compose.onNodeWithText("Conf")
        compose.waitForIdle()
        val initialTop = header.fetchSemanticsNode().boundsInWindow.top
        fun verifyHeader() {
            header.assertIsDisplayed()
            val top = header.fetchSemanticsNode().boundsInWindow.top
            compose.runOnIdle {
                val statusTop = requireNotNull(ViewCompat.getRootWindowInsets(view))
                    .getInsets(WindowInsetsCompat.Type.statusBars()).top
                assertTrue("Exercise a visible status bar", statusTop > 0)
                assertTrue("Keep the home header below the status bar", top >= statusTop)
                assertEquals("Returning must preserve the home header position", initialTop, top, 1f)
            }
        }
        verifyHeader()
        compose.runOnIdle { route = AppRoute.Resources }
        compose.onNodeWithText("Workspace directory").assertIsDisplayed()
        compose.runOnIdle { route = AppRoute.Browser("host", "/projects") }
        compose.onNodeWithText("Create session in", substring = true).assertIsDisplayed()
        compose.runOnIdle { route = route.back() }
        compose.onNodeWithText("Workspace directory").assertIsDisplayed()
        compose.runOnIdle { route = route.back() }
        compose.waitForIdle()
        verifyHeader()
    }
}
