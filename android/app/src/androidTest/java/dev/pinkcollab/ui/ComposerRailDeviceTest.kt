package dev.pinkcollab.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.Host
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.ModelCatalog
import dev.pinkcollab.data.ModelInfo
import dev.pinkcollab.data.PairedHost
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SessionDetail
import dev.pinkcollab.data.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ComposerRailDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun detached_composer_shows_send_without_start_and_sends_on_tap() {
        val host = PairedHost(Host("host", "Desktop", "", "", ""), "https://host", "credential", "client")
        val session = Session("session", "host", "/work", "Work", SessionStatus.Idle, "", false, null,
            "", "", false, null)
        val controls = sessionControls(SessionDetail(session), HostState(host, connection = ConnectionState.Online(1L)),
            SessionDraft(text = "hello"), 0, SessionActivity())
        val sent = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                ComposerRail(controls, onCommand = {}, onExit = {}, onSend = { sent.incrementAndGet() })
            }
        }

        val stop = compose.onNodeWithText("Stop")
        val exit = compose.onNodeWithText("Exit")
        val send = compose.onNodeWithText("Send")
        assertEquals(stop.fetchSemanticsNode().boundsInRoot.left, exit.fetchSemanticsNode().boundsInRoot.left)
        assertEquals(stop.fetchSemanticsNode().boundsInRoot.left, send.fetchSemanticsNode().boundsInRoot.left)
        compose.onNodeWithText("Start").assertDoesNotExist()
        send.performClick()
        compose.runOnIdle { assertEquals(1, sent.get()) }
    }

    @Test fun exited_session_can_start_choose_model_then_send() {
        val host = HostState(
            PairedHost(Host("host", "Desktop", "", "", ""), "https://host", "credential", "client"),
            connection = ConnectionState.Online(1L),
        )
        val exited = Session("session", "host", "/work", "Work", SessionStatus.Idle, "", false, null,
            "", "", false, null)
        val selected = ModelInfo("provider", "test-model", "Test model")
        var page by mutableStateOf(SessionPageState(LoadState.Ready(SessionDetail(exited)), host,
            SessionDraft(text = "hello again"), 0, SessionActivity(), null, null))
        val actions = mutableListOf<SessionAction>()
        var applied: ModelSettingsChanges? = null
        compose.setContent {
            SessionPage(page, onAction = { actions += it }, onApplyModelSettings = {
                applied = it
                true
            })
        }

        compose.onNodeWithContentDescription("Choose model: Select model").performClick()
        compose.onNodeWithText("Start runtime").assertExists()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithText("Start runtime").performClick()
        compose.runOnIdle {
            assertEquals(SessionAction.Command(SessionUserCommand.Start), actions.last())
            page = page.copy(detail = LoadState.Ready(SessionDetail(exited.copy(status = SessionStatus.Starting))),
                activity = SessionActivity(action = true))
        }
        compose.onNodeWithText("Starting OMP…").assertExists()
        compose.runOnIdle {
            page = page.copy(detail = LoadState.Ready(SessionDetail(exited.copy(status = SessionStatus.Idle,
                runtimeAttached = true, generation = "next"))),
                activity = SessionActivity())
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(actions.contains(SessionAction.LoadModels(false)))
            page = page.copy(model = LoadState.Ready(ModelCatalog(listOf(selected), emptyList())))
        }
        compose.onNodeWithTag("provider:provider").performClick()
        compose.onNodeWithContentDescription("Test model, provider").performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle {
            assertEquals(ModelSettingsChanges(selected, null), applied)
            val detail = (page.detail as LoadState.Ready).value
            page = page.copy(detail = LoadState.Ready(detail.copy(model = selected)))
        }
        compose.onNodeWithContentDescription("Choose model: Test model").assertExists()
        compose.onNodeWithText("Send").performClick()
        compose.runOnIdle { assertEquals(SessionAction.Send, actions.last()) }
    }

}
