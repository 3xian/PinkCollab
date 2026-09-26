package dev.pinkcollab.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class GatewayRepositorySafetyTest {
    private class MemoryOutbox : CommandOutboxStorage {
        private val values = mutableMapOf<String, PendingCommand>()
        var rejectWrites = false
        var beforeClaim: (suspend () -> Unit)? = null
        var afterClaim: (suspend () -> Unit)? = null

        override suspend fun find(key: String): PendingCommand? = synchronized(values) { values[key] }
        override suspend fun insertIfAbsent(command: PendingCommand): PendingCommand = synchronized(values) {
            if (rejectWrites) throw IOException("disk unavailable")
            values.getOrPut(command.key) { command }
        }
        override suspend fun transition(key: String, id: String, from: CommandState, to: CommandState): Boolean {
            if (from == CommandState.PREPARED) beforeClaim?.invoke()
            val changed = synchronized(values) {
                if (rejectWrites) throw IOException("disk unavailable")
                val old = values[key]
                if (old?.id != id || old.state != from) false else {
                    values[key] = old.copy(state = to)
                    true
                }
            }
            if (changed && from == CommandState.PREPARED) afterClaim?.invoke()
            return changed
        }
        override suspend fun remove(key: String, id: String) {
            synchronized(values) { if (values[key]?.id == id) values.remove(key) }
        }
        override suspend fun removeIfState(key: String, id: String, state: CommandState): Boolean = synchronized(values) {
            if (values[key]?.id == id && values[key]?.state == state) { values.remove(key); true } else false
        }
        override suspend fun records(): List<PendingCommand> = synchronized(values) { values.values.toList() }
        override suspend fun promptsFor(hostId: String, clientId: String, sessionId: String): List<PendingCommand> =
            records().filter { it.hostId == hostId && it.clientId == clientId && it.sessionId == sessionId && it.type == "prompt" }
        override suspend fun removeHost(hostId: String) { synchronized(values) { values.entries.removeAll { it.value.hostId == hostId } } }
    }

    private fun request(intent: String = "intent-one", message: String = "hello") = CommandRequest(
        "host", "client", "session", "prompt:intent:$intent", "prompt", intent,
    ) { JSONObject().put("delivery", "start").put("message", message) }

    private fun missing(): Nothing = throw GatewayHttpException(404, "operation_not_found", "missing")
    private fun receipt(id: String, status: String) = JSONObject().put("commandId", id).put("status", status)

    @Test fun outbox_key_is_opaque_and_stable() {
        assertEquals(64, request().key.length)
        assertEquals(request().key, request(message = "edited").key)
        assertTrue(!request().key.contains("hello"))
    }

    @Test fun lost_response_retries_only_the_original_id_and_payload() = runBlocking {
        val storage = MemoryOutbox()
        val sent = mutableListOf<JSONObject>()
        var lookups = 0
        val transport = CommandTransport(
            post = { body ->
                sent += body
                if (sent.size == 1) throw IOException("response lost")
                JSONObject().put("operation", receipt(body.getString("commandId"), "accepted"))
            },
            lookup = { lookups++; if (lookups == 2) throw IOException("offline") else missing() },
        )
        try { DurableCommandOutbox(storage).submit(request(), transport); throw AssertionError() } catch (_: IOException) { }
        assertEquals(1, sent.size)
        DurableCommandOutbox(storage).submit(request(message = "changed"), transport)
        assertEquals(2, sent.size)
        assertEquals(sent[0].toString(), sent[1].toString())
        assertEquals("intent-one", sent[1].getString("commandId"))
    }

    @Test fun restored_draft_queries_completed_receipt_without_a_local_marker() = runBlocking {
        val storage = MemoryOutbox()
        var posts = 0
        val transport = CommandTransport(
            post = { body -> posts++; JSONObject().put("operation", receipt(body.getString("commandId"), "succeeded")) },
            lookup = { if (posts == 0) missing() else receipt(it, "succeeded") },
        )
        DurableCommandOutbox(storage).submit(request(), transport)
        assertTrue(storage.records().isEmpty())
        DurableCommandOutbox(storage).submit(request(), transport)
        assertEquals(1, posts)
        assertTrue(storage.records().isEmpty())
    }

    @Test fun lookup_authentication_failure_never_posts_or_discards_a_command() = runBlocking {
        val storage = MemoryOutbox()
        var posts = 0
        val unauthorized = CommandTransport(
            post = { posts++; JSONObject() },
            lookup = { throw GatewayHttpException(401, "authentication_required", "unauthorized") },
        )
        try { DurableCommandOutbox(storage).submit(request(), unauthorized); throw AssertionError() }
        catch (failure: GatewayHttpException) { assertEquals(401, failure.statusCode) }
        assertEquals(0, posts)
        assertTrue(storage.records().isEmpty())

        val pending = PendingCommand(request().key, "host", "client", "session", "intent-one", "prompt", "{}", CommandState.POSTED)
        storage.insertIfAbsent(pending)
        try { DurableCommandOutbox(storage).submit(request(), unauthorized); throw AssertionError() }
        catch (failure: GatewayHttpException) { assertEquals(401, failure.statusCode) }
        assertEquals(0, posts)
        assertEquals(pending, storage.records().single())
    }

    @Test fun only_operation_not_found_permits_a_new_prompt_post() = runBlocking {
        val storage = MemoryOutbox()
        var posts = 0
        for (failure in listOf(
            GatewayHttpException(503, "persistence_unavailable", "unavailable"),
            GatewayHttpException(404, "session_not_found", "session missing"),
            IOException("offline"),
        )) {
            try {
                DurableCommandOutbox(storage).submit(request(), CommandTransport(
                    post = { posts++; JSONObject() }, lookup = { throw failure }))
                throw AssertionError()
            } catch (received: IOException) { assertEquals(failure, received) }
        }
        assertEquals(0, posts)
        assertTrue(storage.records().isEmpty())
    }

    @Test fun deleted_prepared_prompt_cannot_post_after_a_new_draft() = runBlocking {
        val storage = MemoryOutbox()
        val old = PendingCommand(request().key, "host", "client", "session", "intent-one", "prompt",
            JSONObject().put("commandId", "intent-one").put("type", "prompt").put("message", "old").toString())
        storage.insertIfAbsent(old)
        val atClaim = CompletableDeferred<Unit>()
        val resumeClaim = CompletableDeferred<Unit>()
        storage.beforeClaim = { atClaim.complete(Unit); resumeClaim.await() }
        val sent = mutableListOf<String>()
        val transport = CommandTransport(
            post = { body -> sent += body.getString("commandId"); JSONObject().put("operation", receipt(body.getString("commandId"), "accepted")) },
            lookup = { missing() },
        )
        val recovering = async(Dispatchers.Default) { DurableCommandOutbox(storage).recover(old, transport) }
        atClaim.await()
        storage.beforeClaim = null
        DurableCommandOutbox(storage).submit(request("intent-two", "new"), transport)
        resumeClaim.complete(Unit)
        recovering.await()
        assertEquals(listOf("intent-two"), sent)
    }

    @Test fun claimed_old_prompt_blocks_a_new_draft_until_its_receipt_exists() = runBlocking {
        val storage = MemoryOutbox()
        val old = PendingCommand(request().key, "host", "client", "session", "intent-one", "prompt",
            JSONObject().put("commandId", "intent-one").put("type", "prompt").put("message", "old").toString())
        storage.insertIfAbsent(old)
        val claimed = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        storage.afterClaim = { claimed.complete(Unit); resume.await() }
        val sent = mutableListOf<String>()
        val transport = CommandTransport(
            post = { body -> sent += body.getString("commandId"); JSONObject().put("operation", receipt(body.getString("commandId"), "accepted")) },
            lookup = { missing() },
        )
        val recovering = async(Dispatchers.Default) { DurableCommandOutbox(storage).recover(old, transport) }
        claimed.await()
        try { DurableCommandOutbox(storage).submit(request("intent-two"), transport); throw AssertionError() }
        catch (failure: IOException) { assertTrue(failure.message.orEmpty().contains("Earlier prompt")) }
        assertTrue(sent.isEmpty())
        resume.complete(Unit)
        recovering.await()
        assertEquals(listOf("intent-one"), sent)
    }

    @Test fun lost_stop_response_is_lookup_only() = runBlocking {
        val storage = MemoryOutbox()
        val request = CommandRequest("host", "client", "session", "stop", "stop_runtime") {
            JSONObject().put("expectedGeneration", "run")
        }
        var posts = 0
        val transport = CommandTransport(post = { posts++; throw IOException("response lost") }, lookup = { missing() })
        repeat(2) {
            try { DurableCommandOutbox(storage).submit(request, transport); throw AssertionError() } catch (_: IOException) { }
        }
        assertEquals(1, posts)
        assertEquals(CommandState.LOOKUP_ONLY, storage.records().single().state)
    }

    @Test fun failed_persistence_prevents_post() = runBlocking {
        val storage = MemoryOutbox().apply { rejectWrites = true }
        var posts = 0
        try {
            DurableCommandOutbox(storage).submit(request(), CommandTransport(
                post = { posts++; JSONObject() }, lookup = { missing() }))
            throw AssertionError()
        } catch (_: IOException) { }
        assertEquals(0, posts)
    }

    @Test fun explicit_json_null_is_not_a_history_cursor() {
        assertNull(historyCursor(JSONObject("""{"nextCursor":null}""")))
        assertNull(historyCursor(JSONObject("""{"nextCursor":""}""")))
        assertEquals("cursor-one", historyCursor(JSONObject("""{"nextCursor":"cursor-one"}""")))
    }

    @Test fun event_reduction_retries_after_a_concurrent_history_update() {
        val state = MutableStateFlow(mapOf("history" to 0, "event" to 0))
        val read = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val worker = thread {
            updateAtomically(state) { current ->
                if (current.getValue("history") == 0) {
                    read.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                }
                (current + ("event" to 1)) to Unit
            }
        }
        check(read.await(5, TimeUnit.SECONDS))
        state.update { it + ("history" to 1) }
        resume.countDown()
        worker.join(5_000)
        assertEquals(1, state.value["history"])
        assertEquals(1, state.value["event"])
    }
}
