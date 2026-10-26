package dev.pinkcollab.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import dev.pinkcollab.data.SavedHistory
import dev.pinkcollab.data.TimelineItem
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.AttentionResponse
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
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class ComposerRailDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun composer_placeholder_tracks_latest_user_and_ellipsizes_without_restricting_draft() {
        val session = Session("session", "host", "/work", "Work", SessionStatus.Idle, "", false, null,
            "", "", true, "generation")
        val host = HostState(
            PairedHost(Host("host", "Desktop", "", "", ""), "https://host", "credential", "client"),
            connection = ConnectionState.Online(1L),
        )
        val latest = "请检查 Android session 输入框的占位文本，确保这是一条足够长的用户指令，超出可用宽度时末尾显示省略号。\n第二行不能另起一行。"
        val saved = TimelineItem("saved", "user", "Earlier instruction", "", "")
        var detail = SessionDetail(session, snapshotToken = "subscription",
            savedHistory = SavedHistory.Ready(null, listOf(saved), null))
        var page by mutableStateOf(SessionPageState(LoadState.Ready(detail), host,
            SessionDraft(), 0, SessionActivity(), null, null))
        compose.setContent {
            SessionPage(page, onAction = { action ->
                if (action is SessionAction.DraftChanged) page = page.copy(draft = SessionDraft(text = action.text))
            }, onApplyModelSettings = { true })
        }
        // The same text also appears in the timeline; query within the composer.
        fun composerText(text: String) = compose.onAllNodes(
            androidx.compose.ui.test.hasText(text) and
                androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("sessionComposer")),
            useUnmergedTree = true,
        )[0]
        composerText(saved.text).assertIsDisplayed()
        compose.runOnIdle {
            detail = detail.copy(liveItems = listOf(
                TimelineItem("live", "user", latest, "", ""),
                TimelineItem("reply", "assistant", "Not a user instruction", "", ""),
            ))
            page = page.copy(detail = LoadState.Ready(detail))
        }
        val layouts = mutableListOf<TextLayoutResult>()
        composerText(latest).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        compose.runOnIdle {
            assertEquals(1, layouts.single().lineCount)
            assertTrue(layouts.single().isLineEllipsized(0))
        }
        compose.onNodeWithTag("sessionInput").performTextInput("new draft\nsecond line")
        composerText("new draft\nsecond line").assertIsDisplayed()
        compose.runOnIdle {
            page = page.copy(draft = SessionDraft(), detail = LoadState.Ready(SessionDetail(
                session.copy(id = "empty"), snapshotToken = "subscription")))
        }
        composerText("What should OMP do?").assertIsDisplayed()
    }

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
        val controls = sessionControls(SessionDetail(session, snapshotToken = "ready"), HostState(host, connection = ConnectionState.Online(1L)),
            SessionDraft(text = "hello"), 0, SessionActivity())
        val sent = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                ComposerRail(controls, onCommand = {}, onExit = {}, onSend = { sent.incrementAndGet() })
            }
        }

        val stop = compose.onNodeWithText("Stop")
        val exit = compose.onNodeWithText("End")
        val send = compose.onNodeWithText("Send")
        assertEquals(stop.fetchSemanticsNode().boundsInRoot.left, exit.fetchSemanticsNode().boundsInRoot.left)
        assertEquals(stop.fetchSemanticsNode().boundsInRoot.left, send.fetchSemanticsNode().boundsInRoot.left)
        compose.onNodeWithText("Start").assertDoesNotExist()
        send.performClick()
        compose.runOnIdle { assertEquals(1, sent.get()) }
    }

    @Test fun transport_start_failure_without_receipt_is_retryable_and_pending_survives_reopening() {
        val host = HostState(
            PairedHost(Host("host", "Desktop", "", "", ""), "https://host", "credential", "client"),
            connection = ConnectionState.Online(1L),
        )
        val session = Session("session", "host", "/work", "Work", SessionStatus.Idle, "", false, null,
            "", "", false, null)
        val initial = SessionDetail(session, snapshotToken = "subscription")
        val page = SessionPageState(LoadState.Ready(initial), host,
            SessionDraft(), 0, SessionActivity(), null, null)
        val key = SessionKey(session.hostId, session.id)
        var requests = 0
        val actions = object : SessionActions {
            override suspend fun command(session: Session, command: SessionUserCommand) {
                check(command == SessionUserCommand.Start)
                requests++
                if (requests == 1) throw IOException("Start outcome was not confirmed")
            }
            override suspend fun upload(session: Session, file: SelectedFile) = error("Unexpected upload")
            override suspend fun prompt(session: Session, message: String, fileIds: List<String>, intentId: String) = error("Unexpected prompt")
            override suspend fun respond(session: Session, response: AttentionResponse) = error("Unexpected response")
            override suspend fun selectModel(session: Session, model: ModelInfo) = error("Unexpected model selection")
            override suspend fun setThinkingLevel(session: Session, level: String) = error("Unexpected thinking change")
            override suspend fun setFastMode(session: Session, enabled: Boolean) = error("Unexpected fast change")
            override suspend fun loadEarlierHistory(session: Session) = error("Unexpected history load")
        }
        compose.setContent {
            val scope = rememberCoroutineScope()
            val coordinator = remember {
                SessionOperations(scope, actions, SessionDraftStore(), { _, _ -> }, {}).also {
                    it.updateDetails(mapOf(key to initial))
                }
            }
            val starts by coordinator.runtimeStarts.collectAsState()
            val operations by coordinator.operations.collectAsState()
            SessionPage(page.copy(runtimeStart = starts[key], activity = operations.activity(key)),
                onAction = { action ->
                    if (action is SessionAction.Command) coordinator.command(session, action.command)
                }, onApplyModelSettings = { true })
        }
        compose.onNodeWithContentDescription("Choose model: model").performClick()
        compose.onNodeWithText("Could not start OMP\nStart outcome was not confirmed").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsEnabled().performClick()
        compose.onNodeWithText("Could not start OMP", substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, requests) }
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithContentDescription("Choose model: model").performClick()
        compose.onNodeWithText("Could not start OMP", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Starting OMP…").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, requests) }
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

        compose.onNodeWithContentDescription("Choose model: model").performClick()
        compose.onNodeWithText("Choose another model").assertDoesNotExist()
        compose.onNodeWithText("Keep default").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(SessionAction.Command(SessionUserCommand.Start)), actions)
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
