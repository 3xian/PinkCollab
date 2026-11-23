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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import dev.pinkcollab.data.SavedHistory
import dev.pinkcollab.data.TimelineItem
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
            }, onApplyModelSettings = {})
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
            SessionPage(page, onAction = {}, onApplyModelSettings = {
                applied += it
                if (applied.size == 1) error("Fast mode change was not confirmed.")
            })
        }
        compose.onNodeWithContentDescription("Fast mode").assertIsEnabled().performClick()
        compose.onNodeWithText("Fast mode change was not confirmed.").assertIsDisplayed()
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

    @Test fun detached_picker_reloads_catalog_without_start_and_disables_apply_while_resyncing() {
        val host = HostState(
            PairedHost(Host("host", "Desktop", "", "", ""), "https://host", "credential", "client"),
            connection = ConnectionState.Online(1L),
        )
        val session = Session("session", "host", "/work", "Work", SessionStatus.Idle, "", false, null,
            "", "", false, null)
        val model = ModelInfo("provider", "model", "Saved model")
        val initial = SessionDetail(session, model = model, snapshotToken = "subscription")
        var page by mutableStateOf(SessionPageState(LoadState.Ready(initial), host,
            SessionDraft(), 0, SessionActivity(), null, LoadState.Ready(ModelCatalog(listOf(model), emptyList()))))
        val actions = mutableListOf<SessionAction>()
        compose.setContent {
            SessionPage(page, onAction = { actions += it }, onApplyModelSettings = { error("Unexpected apply") })
        }
        compose.onNodeWithContentDescription("Choose model: model").performClick()
        compose.onNodeWithText("Reload").assertIsEnabled().performClick()
        compose.onNodeWithTag("provider:provider").performClick()
        compose.onNodeWithText("Current", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Saved model, provider").performClick()
        compose.onNodeWithText("Apply").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(listOf(SessionAction.LoadModels(false), SessionAction.LoadModels(true)), actions)
            page = page.copy(detail = LoadState.Ready(initial.copy(snapshotToken = null)))
        }
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        compose.onNodeWithText("Reload").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithContentDescription("Choose model: model").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(2, actions.size) }
    }

    @Test fun detached_session_browses_before_apply_and_can_send_after_success() {
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
                page = page.copy(detail = LoadState.Ready(initialDetail.copy(
                    session = exited.copy(runtimeAttached = true, generation = "next"), model = selected)))
            })
        }

        compose.onNodeWithContentDescription("Choose model: model").performClick()
        compose.runOnIdle {
            assertEquals(listOf(SessionAction.LoadModels(false)), actions)
            page = page.copy(model = LoadState.Ready(ModelCatalog(listOf(selected), emptyList())))
        }
        compose.onNodeWithTag("provider:provider").performClick()
        compose.onNodeWithContentDescription("Test model, provider").performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle {
            assertEquals(ModelSettingsChanges(selected, null), applied)
            assertTrue(actions.none { it is SessionAction.Command })
        }
        compose.onNodeWithContentDescription("Choose model: Test model").assertExists()
        compose.onNodeWithText("Send").performClick()
        compose.runOnIdle { assertEquals(SessionAction.Send, actions.last()) }
    }

}
