package dev.pinkcollab.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class GatewayRepositorySafetyTest {
    private class RecordingTransport(private val post: (JSONObject) -> JSONObject) : GatewayTransport {
        override val client = OkHttpClient()
        val posts = mutableListOf<JSONObject>()
        override fun validateURL(value: String) = value
        override suspend fun request(url: String, credential: String?, path: String, method: String,
            body: JSONObject?, query: Pair<String, String>?): String {
            if (method == "GET") throw GatewayHttpException(404, "operation_not_found", "missing")
            check(path.endsWith("/commands"))
            val command = JSONObject(body.toString())
            posts += command
            return post(command).toString()
        }
        override suspend fun upload(url: String, credential: String, path: String, name: String,
            bytes: ByteArray): String = error("unexpected upload")
    }

    private fun accepted(body: JSONObject) = JSONObject().put("operation", receipt(body.getString("commandId"), "accepted"))

    private fun runtimeState(host: PairedHost, generation: String): AppState {
        val session = Session("session", "host", "/tmp", "Work", SessionStatus.Running, "Working", false, null,
            "2026-01-01", "2026-01-01", true, generation, RuntimeExecution.Active)
        return AppState(hosts = mapOf("host" to HostState(host, sessions = listOf(session))),
            details = mapOf(SessionKey("host", "session") to SessionDetail(session)))
    }

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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun forgetting_host_waits_for_command_and_removes_its_pending_record() = runTest {
        val storage = MemoryOutbox()
        val gate = HostCommandGate()
        val posted = CompletableDeferred<Unit>()
        val releasePost = CompletableDeferred<Unit>()
        val host = PairedHost(Host("host", "Host", "", "", ""), "https://host", "credential", "client")
        var paired = true
        var posts = 0
        val transport = object : GatewayTransport {
            override val client = OkHttpClient()
            override fun validateURL(value: String) = value
            override suspend fun request(url: String, credential: String?, path: String, method: String,
                body: JSONObject?, query: Pair<String, String>?): String {
                check(path.endsWith("/commands"))
                posts++
                posted.complete(Unit)
                releasePost.await()
                return JSONObject().put("operation", receipt(body!!.getString("commandId"), "accepted")).toString()
            }
            override suspend fun upload(url: String, credential: String, path: String, name: String,
                bytes: ByteArray): String = error("unexpected upload")
        }
        val dispatcher = CommandDispatcher(MutableStateFlow(AppState()),
            { check(paired) { "Host removed" }; host }, transport, storage, gate)

        val sending = async { dispatcher.command("host", "session", "start") }
        posted.await()
        val forgetting = async {
            gate.withHostRemoval("host") {
                paired = false
                dispatcher.removeHost("host")
            }
        }
        runCurrent()
        assertFalse(forgetting.isCompleted)
        assertTrue(runCatching { dispatcher.command("host", "other-session", "start") }.isFailure)
        assertEquals(1, posts)
        releasePost.complete(Unit)
        sending.await()
        forgetting.await()

        assertTrue(storage.records().isEmpty())
        assertEquals(1, posts)
        assertTrue(runCatching { dispatcher.command("host", "session", "start") }.isFailure)
        assertEquals(1, posts)
    }

    @Test fun commands_for_the_same_host_can_still_run_concurrently() = runTest {
        val gate = HostCommandGate()
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val first = async {
            gate.withHost("host") {
                firstStarted.complete(Unit)
                finishFirst.await()
            }
        }
        firstStarted.await()
        var secondFinished = false
        gate.withHost("host") { secondFinished = true }
        assertTrue(secondFinished)
        finishFirst.complete(Unit)
        first.await()
    }

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

    @Test fun stale_lookup_only_stop_does_not_block_a_new_runtime() = runBlocking {
        val host = PairedHost(Host("host", "Host", "", "", ""), "https://host", "credential", "client")
        val state = MutableStateFlow(runtimeState(host, "run-a"))
        val storage = MemoryOutbox()
        val transport = RecordingTransport { body ->
            if (body.getString("expectedGeneration") == "run-a") throw IOException("response lost")
            accepted(body)
        }
        val dispatcher = CommandDispatcher(state, { host }, transport, storage, HostCommandGate())

        repeat(2) {
            try { dispatcher.command("host", "session", "stop"); throw AssertionError() } catch (_: IOException) { }
        }
        assertEquals(1, transport.posts.size)
        assertEquals(CommandState.LOOKUP_ONLY, storage.records().single().state)

        state.value = runtimeState(host, "run-b")
        dispatcher.command("host", "session", "stop")

        assertEquals(listOf("run-a", "run-b"), transport.posts.map { it.getString("expectedGeneration") })
        val records = storage.records()
        assertEquals(2, records.size)
        assertNotEquals(records[0].key, records[1].key)
        assertTrue(records.all { it.state == CommandState.LOOKUP_ONLY })
    }

    @Test fun runtime_specific_actions_use_their_payload_generation_in_the_key() = runBlocking {
        val host = PairedHost(Host("host", "Host", "", "", ""), "https://host", "credential", "client")
        suspend fun checkScoped(action: suspend (CommandDispatcher) -> Unit) {
            val state = MutableStateFlow(runtimeState(host, "run-a"))
            val storage = MemoryOutbox()
            val transport = RecordingTransport(::accepted)
            val dispatcher = CommandDispatcher(state, { host }, transport, storage, HostCommandGate())
            action(dispatcher)
            state.value = runtimeState(host, "run-b")
            action(dispatcher)
            assertEquals(listOf("run-a", "run-b"), transport.posts.map { it.getString("expectedGeneration") })
            assertEquals(2, storage.records().map { it.key }.toSet().size)
        }

        checkScoped { it.command("host", "session", "interrupt") }
        checkScoped { it.respond("host", "session", AttentionResponse.Value("input-1", "answer")) }
        checkScoped { it.selectModel("host", "session", ModelInfo("provider", "model", "Model")) }
        checkScoped { it.setThinkingLevel("host", "session", "high") }
    }

    @Test fun runtime_commands_use_the_same_host_summary_fallback() = runBlocking {
        val host = PairedHost(Host("host", "Host", "", "", ""), "https://host", "credential", "client")
        val state = MutableStateFlow(runtimeState(host, "run-a").copy(details = emptyMap()))
        val transport = RecordingTransport(::accepted)
        val dispatcher = CommandDispatcher(state, { host }, transport, MemoryOutbox(), HostCommandGate())

        dispatcher.selectModel("host", "session", ModelInfo("provider", "model", "Model"))
        dispatcher.setThinkingLevel("host", "session", "high")
        dispatcher.command("host", "session", "stop")

        assertEquals(listOf("run-a", "run-a", "run-a"), transport.posts.map { it.getString("expectedGeneration") })
    }

    @Test fun stop_key_and_payload_capture_the_same_generation() = runBlocking {
        val host = PairedHost(Host("host", "Host", "", "", ""), "https://host", "credential", "client")
        val state = MutableStateFlow(runtimeState(host, "run-a"))
        val storage = MemoryOutbox()
        val transport = RecordingTransport(::accepted)
        val dispatcher = CommandDispatcher(state, { state.value = runtimeState(host, "run-b"); host },
            transport, storage, HostCommandGate())

        dispatcher.command("host", "session", "stop")

        assertEquals("run-a", transport.posts.single().getString("expectedGeneration"))
        val oldKey = storage.records().single().key
        dispatcher.command("host", "session", "stop")
        assertEquals("run-b", transport.posts.last().getString("expectedGeneration"))
        assertNotEquals(oldKey, storage.records().last().key)
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
