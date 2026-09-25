package dev.pinkcollab.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlin.concurrent.thread

class GatewayRepositorySafetyTest {
    @Test fun lost_post_and_receipt_response_do_not_send_the_prompt_again() = runBlocking {
        val commands = PendingCommandStore()
        val sent = mutableListOf<JSONObject>()
        var delivery = "start"
        var lookups = 0
        var lookedUpId: String? = null
        val post: suspend (JSONObject) -> JSONObject = { body ->
            sent += body
            throw IOException("POST response lost")
        }
        val lookup: suspend (String) -> JSONObject = { commandId ->
            lookups++
            lookedUpId = commandId
            if (lookups == 1) throw IOException("receipt lookup failed")
            JSONObject().put("commandId", commandId).put("status", "succeeded")
        }
        val fields = { JSONObject().put("delivery", delivery).put("message", "hello") }
        try {
            submitPendingCommand(commands, "host:session:prompt:hello", "prompt", fields, post, lookup)
            throw AssertionError("the first attempt must remain unconfirmed")
        } catch (_: IOException) {
        }
        delivery = "steer"
        submitPendingCommand(commands, "host:session:prompt:hello", "prompt", fields, post, lookup)
        assertEquals(1, sent.size)
        assertEquals("start", sent.single().getString("delivery"))
        assertEquals(sent.single().getString("commandId"), lookedUpId)
    }

    @Test fun server_persistence_error_retries_with_the_same_id_and_payload() = runBlocking {
        val commands = PendingCommandStore()
        val sent = mutableListOf<JSONObject>()
        var delivery = "start"
        val post: suspend (JSONObject) -> JSONObject = { body ->
            sent += body
            if (sent.size == 1) throw GatewayHttpException(503, "persistence_unavailable", "Receipt unavailable")
            JSONObject().put("receiptStored", true).put("operation", JSONObject().put("status", "running"))
        }
        val lookup: suspend (String) -> JSONObject = { throw IOException("Receipt unavailable") }
        val fields = { JSONObject().put("delivery", delivery).put("message", "hello") }
        try {
            submitPendingCommand(commands, "host:session:prompt:hello", "prompt", fields, post, lookup)
            throw AssertionError("the first attempt must remain unconfirmed")
        } catch (_: IOException) {
        }
        delivery = "steer"
        submitPendingCommand(commands, "host:session:prompt:hello", "prompt", fields, post, lookup)
        assertEquals(2, sent.size)
        assertEquals(sent[0].toString(), sent[1].toString())
        assertEquals("start", sent[1].getString("delivery"))
    }

    @Test fun pending_command_keeps_the_original_payload_after_runtime_state_changes() {
        val commands = PendingCommandStore()
        var delivery = "start"
        val first = commands.getOrCreate("host:session:prompt:hello") {
            PendingCommand("command-one", JSONObject().put("delivery", delivery).put("message", "hello").toString())
        }
        delivery = "steer"
        val retry = commands.getOrCreate("host:session:prompt:hello") {
            PendingCommand("command-two", JSONObject().put("delivery", delivery).put("message", "hello").toString())
        }
        assertSame(first, retry)
        assertEquals("command-one", retry.id)
        assertEquals("start", JSONObject(retry.payload).getString("delivery"))
        commands.remove("host:session:prompt:hello", retry)
        assertEquals("command-two", commands.getOrCreate("host:session:prompt:hello") {
            PendingCommand("command-two", "{}")
        }.id)
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
