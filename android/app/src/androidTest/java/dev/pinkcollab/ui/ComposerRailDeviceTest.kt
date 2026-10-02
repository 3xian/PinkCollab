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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.Host
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.ModelCatalog
import dev.pinkcollab.data.ModelInfo
import dev.pinkcollab.data.OperationReceipt
import dev.pinkcollab.data.OperationStatus
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

    @Test fun fast_switch_is_clickable_for_detached_and_attached_sessions_without_fast_state() {
        val host = HostState(
            PairedHost(Host("host", "Desktop", "", "", ""), "https://host", "credential", "client"),
            connection = ConnectionState.Online(1L),
        )
        val detached = Session("session", "host", "/work", "Work", SessionStatus.Idle, "", false, null,
            "", "", false, null)
        var page by mutableStateOf(SessionPageState(LoadState.Ready(SessionDetail(detached, snapshotToken = "subscription")), host,
            SessionDraft(), 0, SessionActivity(), null, null))
        val applied = mutableListOf<ModelSettingsChanges>()
        compose.setContent {
            SessionPage(page, onAction = {}, onApplyModelSettings = { applied += it; true })
        }
        compose.onNodeWithContentDescription("Fast mode").assertIsEnabled().performClick()
        compose.runOnIdle {
            page = page.copy(detail = LoadState.Ready(SessionDetail(
                detached.copy(runtimeAttached = true, generation = "started"),
                model = ModelInfo("provider", "model", "Model"),
                snapshotToken = "subscription",
            )))
        }
        compose.onNodeWithContentDescription("Fast mode").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(ModelSettingsChanges(null, null, true), ModelSettingsChanges(null, null, true)), applied)
        }
    }

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

    @Test fun fast_start_failure_shows_current_error_and_retry_clears_previous_error() {
        val host = HostState(
            PairedHost(Host("host", "Desktop", "", "", ""), "https://host", "credential", "client"),
            connection = ConnectionState.Online(1L),
        )
        val session = Session("session", "host", "/work", "Work", SessionStatus.Idle, "", false, null,
            "", "", false, null)
        val oldFailure = OperationReceipt("old-start", OperationStatus.Failed, "start_runtime",
            errorMessage = "Old failure")
        val initial = SessionDetail(session, snapshotToken = "subscription", operations = listOf(oldFailure))
        var page by mutableStateOf(SessionPageState(LoadState.Ready(initial), host,
            SessionDraft(), 0, SessionActivity(), null, null))
        var requests = 0
        compose.setContent {
            SessionPage(page, onAction = { action ->
                if (action == SessionAction.Command(SessionUserCommand.Start)) {
                    requests++
                    if (requests == 1) {
                        // The failure arrives without a rendered Starting or busy frame.
                        page = page.copy(detail = LoadState.Ready(initial.copy(operations = listOf(
                            oldFailure, OperationReceipt("new-start", OperationStatus.Failed, "start_runtime",
                                errorMessage = "previous runtime exit is not confirmed")))))
                    }
                }
            }, onApplyModelSettings = { true })
        }
        compose.onNodeWithContentDescription("Choose model: OMP default").performClick()
        compose.onNodeWithText("Could not start OMP", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Choose another model").performClick()
        compose.onNodeWithText("Could not start OMP\nprevious runtime exit is not confirmed").assertIsDisplayed()
        compose.onNodeWithText("Choose another model").assertIsEnabled().performClick()
        compose.onNodeWithText("Could not start OMP", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, requests) }
        compose.onNodeWithText("Keep default").performClick()
        compose.onNodeWithContentDescription("Choose model: OMP default").performClick()
        compose.onNodeWithText("Could not start OMP", substring = true).assertDoesNotExist()
    }

    @Test fun exited_session_can_start_choose_model_then_send() {
        val host = HostState(
            PairedHost(Host("host", "Desktop", "", "", ""), "https://host", "credential", "client"),
            connection = ConnectionState.Online(1L),
        )
        val exited = Session("session", "host", "/work", "Work", SessionStatus.Idle, "", false, null,
            "", "", false, null)
        val selected = ModelInfo("provider", "test-model", "Test model")
        val initialDetail = SessionDetail(exited, snapshotToken = "subscription")
        var page by mutableStateOf(SessionPageState(LoadState.Ready(initialDetail), host,
            SessionDraft(text = "hello again"), 0, SessionActivity(), null, null))
        val actions = mutableListOf<SessionAction>()
        var applied: ModelSettingsChanges? = null
        compose.setContent {
            SessionPage(page, onAction = { actions += it }, onApplyModelSettings = {
                applied = it
                true
            })
        }

        compose.onNodeWithContentDescription("Choose model: OMP default").performClick()
        compose.onNodeWithText("Choose another model").assertExists()
        compose.runOnIdle { assertTrue(actions.isEmpty()) }
        compose.onNodeWithText("Choose another model").performClick()
        compose.runOnIdle {
            assertEquals(SessionAction.Command(SessionUserCommand.Start), actions.last())
            page = page.copy(detail = LoadState.Ready(initialDetail.copy(session = exited.copy(status = SessionStatus.Starting))),
                activity = SessionActivity(action = true))
        }
        compose.onNodeWithText("Starting OMP…").assertExists()
        compose.runOnIdle {
            page = page.copy(detail = LoadState.Ready(initialDetail.copy(session = exited.copy(status = SessionStatus.Idle,
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
