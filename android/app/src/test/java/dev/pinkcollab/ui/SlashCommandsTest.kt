package dev.pinkcollab.ui

import dev.pinkcollab.data.Session
import dev.pinkcollab.data.SessionStatus
import dev.pinkcollab.data.SlashCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SlashCommandsTest {
    private val session = Session("session", "host", "/work", "Work", SessionStatus.Idle, "Ready",
        false, null, "", "", true, "generation-1")
    private val key = SessionKey("host", "session")
    private val command = SlashCommand("compact", "builtin", aliases = listOf("summarize"))

    @Test fun only_command_name_typing_opens_suggestions() {
        assertEquals("", slashCommandQuery("/"))
        assertEquals("CoM", slashCommandQuery("/CoM"))
        listOf("", "hello /compact", " /compact", "/compact ", "/compact argument", "/compact\n", "/compact\t").forEach {
            assertNull(slashCommandQuery(it))
        }
    }

    @Test fun canonical_names_and_alias_prefixes_match_without_case_sensitivity() {
        val other = SlashCommand("model", "builtin")
        val commands = listOf(command, other)
        assertEquals(commands, matchingSlashCommands(commands, ""))
        assertEquals(listOf(command), matchingSlashCommands(commands, "COM"))
        assertEquals(listOf(command), matchingSlashCommands(commands, "SuM"))
        assertTrue(matchingSlashCommands(commands, "missing").isEmpty())
        assertTrue(matchingSlashCommands(emptyList(), "").isEmpty())
    }

    @Test fun stale_ready_catalog_is_hidden_before_replacement_loading_effect_runs() {
        val ready = LoadState.Ready(SessionCommands("generation-1", listOf(command)))
        assertEquals(ready, currentCommands(ready, "generation-1"))
        assertNull(currentCommands(ready, "generation-2"))
        assertNull(currentCommands(ready, null))
    }

    @Test fun duplicate_requests_are_suppressed_and_empty_catalog_is_cached() = runTest {
        val result = CompletableDeferred<List<SlashCommand>>()
        var requests = 0
        val loader = SessionCommandLoader(backgroundScope) { requests++; result.await() }
        loader.load(session)
        loader.load(session)
        runCurrent()
        assertEquals(1, requests)
        assertEquals(LoadState.Loading, loader.state.value[key])
        result.complete(emptyList())
        runCurrent()
        assertEquals(LoadState.Ready(SessionCommands(session.generation, emptyList())), loader.state.value[key])
        loader.load(session)
        runCurrent()
        assertEquals(1, requests)
    }

    @Test fun failed_catalog_can_retry_without_touching_draft_or_send_controls() = runTest {
        var requests = 0
        val loader = SessionCommandLoader(backgroundScope) {
            if (++requests == 1) error("Commands unavailable")
            listOf(command)
        }
        loader.load(session)
        runCurrent()
        assertEquals(LoadState.Failed("Commands unavailable"), loader.state.value[key])
        loader.load(session, force = true)
        assertEquals(LoadState.Loading, loader.state.value[key])
        runCurrent()
        assertEquals(LoadState.Ready(SessionCommands(session.generation, listOf(command))), loader.state.value[key])
    }

    @Test fun restarted_runtime_discards_cache_and_old_non_cancellable_result_cannot_win() = runTest {
        val oldResult = CompletableDeferred<List<SlashCommand>>()
        val newResult = CompletableDeferred<List<SlashCommand>>()
        val loader = SessionCommandLoader(backgroundScope) {
            withContext(NonCancellable) {
                if (it.generation == session.generation) oldResult.await() else newResult.await()
            }
        }
        loader.load(session)
        runCurrent()
        val restarted = session.copy(generation = "generation-2")
        loader.updateSessions(listOf(restarted))
        assertFalse(key in loader.state.value)
        loader.load(restarted)
        runCurrent()
        newResult.complete(listOf(command))
        runCurrent()
        oldResult.complete(listOf(SlashCommand("obsolete", "extension")))
        runCurrent()
        assertEquals(LoadState.Ready(SessionCommands(restarted.generation, listOf(command))), loader.state.value[key])
    }

    @Test fun restart_and_detach_clear_ready_commands_even_when_composer_is_not_open() = runTest {
        val loader = SessionCommandLoader(backgroundScope) { listOf(command) }
        loader.load(session)
        runCurrent()
        loader.updateSessions(listOf(session.copy(generation = "generation-2")))
        assertNull(loader.state.value[key])
        loader.load(session)
        runCurrent()
        loader.updateSessions(listOf(session.copy(runtimeAttached = false)))
        assertNull(loader.state.value[key])
    }

    @Test fun sessions_do_not_share_catalogs_and_forgetting_host_fences_late_failure() = runTest {
        val finish = CompletableDeferred<Unit>()
        val sibling = session.copy(id = "sibling")
        val siblingKey = SessionKey("host", "sibling")
        val loader = SessionCommandLoader(backgroundScope) {
            if (it.id == session.id) withContext(NonCancellable) { finish.await(); error("Old failure") }
            else listOf(command)
        }
        loader.load(session)
        loader.load(sibling)
        runCurrent()
        assertEquals(LoadState.Loading, loader.state.value[key])
        assertEquals(LoadState.Ready(SessionCommands(sibling.generation, listOf(command))), loader.state.value[siblingKey])
        loader.removeSession(siblingKey)
        assertNull(loader.state.value[siblingKey])
        loader.removeHost("host")
        finish.complete(Unit)
        runCurrent()
        assertTrue(loader.state.value.isEmpty())
    }
}
