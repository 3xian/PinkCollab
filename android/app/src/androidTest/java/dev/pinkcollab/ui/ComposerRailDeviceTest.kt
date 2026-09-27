package dev.pinkcollab.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pinkcollab.data.ConnectionState
import dev.pinkcollab.data.Host
import dev.pinkcollab.data.HostState
import dev.pinkcollab.data.PairedHost
import dev.pinkcollab.data.RuntimeExecution
import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SessionDetail
import dev.pinkcollab.data.SessionStatus
import org.junit.Assert.assertEquals
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
            "", "", false, null, RuntimeExecution.Unknown)
        val controls = sessionControls(SessionDetail(session), HostState(host, connection = ConnectionState.Online(1L)),
            SessionDraft(text = "hello"), 0, SessionActivity(), false)
        val sent = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                ComposerRail(controls, onCommand = {}, onExit = {}, onSend = { sent.incrementAndGet() })
            }
        }

        compose.onNodeWithText("Stop").assertExists()
        compose.onNodeWithText("Exit").assertExists()
        compose.onNodeWithText("Start").assertDoesNotExist()
        compose.onNodeWithText("Send").performClick()
        compose.runOnIdle { assertEquals(1, sent.get()) }
    }
}
