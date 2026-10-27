package dev.pinkcollab.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.Host
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.PairedHost
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SessionDetail
import dev.pinkcollab.data.SessionStatus
import dev.pinkcollab.data.SlashCommand
import dev.pinkcollab.data.SlashCommandInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SlashCommandsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val session = Session("session", "host", "/work", "Work", SessionStatus.Idle, "", false, null,
        "", "", true, "generation")
    private val host = HostState(
        PairedHost(Host("host", "Desktop", "", "", ""), "https://host", "credential", "client"),
        connection = ConnectionState.Online(1L),
    )
    private val commands = LoadState.Ready(SessionCommands("generation", listOf(
        SlashCommand("compact", "builtin", listOf("summarize"), "Summarize the conversation",
            SlashCommandInput("Optional compaction instructions")),
        SlashCommand("model", "builtin", description = "Choose a model"),
    )))

    @Test fun alias_selection_inserts_canonical_draft_without_sending_and_send_keeps_the_prompt_path() {
        var page by mutableStateOf(SessionPageState(
            LoadState.Ready(SessionDetail(session, snapshotToken = "ready")), host,
            SessionDraft(), 0, SessionActivity(), null, null, commands = commands,
        ))
        val sent = mutableListOf<String>()
        compose.setContent {
            SessionPage(page, onAction = { action ->
                when (action) {
                    is SessionAction.DraftChanged -> page = page.copy(draft = SessionDraft(text = action.text))
                    SessionAction.Send -> sent += page.draft.text
                    else -> Unit
                }
            }, onApplyModelSettings = { true })
        }
        compose.onNodeWithTag("sessionInput").performClick().performTextInput("/SuM")
        compose.onNodeWithTag("slashCommand:compact").assertIsDisplayed()
        compose.onNodeWithTag("slashCommand:model").assertDoesNotExist()
        compose.onNodeWithText("Summarize the conversation").assertIsDisplayed()
        compose.onNodeWithText("Optional compaction instructions").assertIsDisplayed()
        compose.onNodeWithText("Send").assertIsEnabled()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        instrumentation.targetContext.getExternalFilesDir(null)!!.resolve("slash-commands.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
        compose.onNodeWithTag("slashCommand:compact").performClick()
        compose.onNodeWithTag("sessionInput").assertTextEquals("/compact ")
        compose.onNodeWithTag("slashCommandSuggestions").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), sent) }
        compose.onNodeWithText("Send").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("/compact "), sent) }
    }

    @Test fun error_retry_empty_unknown_and_runtime_restart_never_disable_send_or_keep_old_names() {
        var page by mutableStateOf(SessionPageState(
            LoadState.Ready(SessionDetail(session, snapshotToken = "ready")), host,
            SessionDraft(text = "/"), 0, SessionActivity(), null, null, commands = LoadState.Loading,
        ))
        val loads = mutableListOf<SessionAction.LoadCommands>()
        compose.setContent {
            SessionPage(page, onAction = {
                if (it is SessionAction.LoadCommands) loads += it
                if (it is SessionAction.DraftChanged) page = page.copy(draft = SessionDraft(text = it.text))
            }, onApplyModelSettings = { true })
        }
        compose.onNodeWithText("Loading commands…").assertIsDisplayed()
        compose.onNodeWithText("Send").assertIsEnabled()
        compose.runOnIdle { page = page.copy(commands = LoadState.Failed("Connection interrupted")) }
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(SessionAction.LoadCommands(force = true), loads.last()) }
        compose.runOnIdle { page = page.copy(commands = LoadState.Ready(SessionCommands("generation", emptyList()))) }
        compose.onNodeWithText("No commands available. You can still send your draft.").assertIsDisplayed()
        compose.onNodeWithText("Send").assertIsEnabled()
        compose.runOnIdle { page = page.copy(commands = commands, draft = SessionDraft(text = "/unknown")) }
        compose.onNodeWithText("No matching commands. You can still send your draft.").assertIsDisplayed()
        compose.onNodeWithText("Send").assertIsEnabled()
        compose.runOnIdle { page = page.copy(draft = SessionDraft(text = "/")) }
        compose.onNodeWithTag("slashCommand:compact").assertIsDisplayed()
        compose.runOnIdle {
            page = page.copy(detail = LoadState.Ready(SessionDetail(session.copy(generation = "restarted"), snapshotToken = "new")))
        }
        compose.onNodeWithTag("slashCommand:compact").assertDoesNotExist()
        compose.onNodeWithText("Loading commands…").assertIsDisplayed()
        compose.onNodeWithText("Send").assertIsEnabled()
    }
}
