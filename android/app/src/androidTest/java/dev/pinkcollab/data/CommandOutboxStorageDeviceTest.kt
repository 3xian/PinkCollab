package dev.pinkcollab.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CommandOutboxStorageDeviceTest {
    @Test fun upgrade_fences_old_picker_commands_but_not_new_explicit_starts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val types = listOf("select_model", "set_thinking_level", "set_fast_mode", "start_runtime", "prompt")
        fun command(id: String, type: String, state: CommandState) = PendingCommand(
            id, "host", "client", "session", id, type,
            JSONObject().put("type", type).put("commandId", id).toString(), state,
        )
        val rows = types.flatMap { type ->
            listOf(CommandState.PREPARED, CommandState.POSTED).map { command("$type-$it", type, it) }
        }
        for (oldVersion in listOf(1, 2)) {
            val name = "outbox-upgrade-$oldVersion-${UUID.randomUUID()}.db"
            try {
                // v1 and v2 have the same table layout and encryption key. Seed
                // real encrypted rows, then reopen through SQLiteOpenHelper's upgrade.
                SqliteCommandOutboxStorage(context, name).use { seed ->
                    for (row in rows) seed.insertIfAbsent(row)
                }
                context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { it.version = oldVersion }
                SqliteCommandOutboxStorage(context, name).use { storage ->
                    val outbox = DurableCommandOutbox(storage)
                    val missingReceipt = CommandTransport(
                        post = { error("Legacy command must not be posted") },
                        lookup = { throw GatewayHttpException(404, "operation_not_found", "missing") },
                    )
                    for (row in rows) {
                        val stored = storage.find(row.key)
                        if (oldVersion == 2 && row.type == "prompt") {
                            assertEquals(row, stored)
                        } else if (row.state == CommandState.PREPARED) {
                            assertNull(stored)
                        } else {
                            val fenced = checkNotNull(stored)
                            assertEquals(row.copy(state = CommandState.LOOKUP_ONLY), fenced)
                            assertTrue(runCatching { outbox.recover(fenced, missingReceipt) }.exceptionOrNull() is IOException)
                            assertEquals(fenced, storage.find(row.key))
                        }
                    }
                    val start = storage.insertIfAbsent(command("explicit-start", "start_runtime", CommandState.PREPARED))
                    var posts = 0
                    outbox.recover(start, CommandTransport(
                        post = { body ->
                            posts++
                            assertEquals(start.id, body.getString("commandId"))
                            JSONObject().put("operation", JSONObject().put("id", start.id).put("state", "succeeded"))
                        },
                        lookup = { error("A newly prepared start has not yet been sent") },
                    ))
                    assertEquals(1, posts)
                    assertNull(storage.find(start.key))
                }
            } finally {
                context.deleteDatabase(name)
            }
        }
    }

    @Test fun encrypted_row_survives_a_new_storage_instance_and_only_one_claim_wins() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val hostId = "device-test-${UUID.randomUUID()}"
        val request = CommandRequest(hostId, "client", "session", "prompt:intent:${UUID.randomUUID()}", "prompt") { JSONObject() }
        val payload = JSONObject().put("message", "secret-device-test-${UUID.randomUUID()}").toString()
        val command = PendingCommand(request.key, hostId, "client", "session", UUID.randomUUID().toString(), "prompt", payload)
        val first = SqliteCommandOutboxStorage(context)
        try {
            assertEquals(command, first.insertIfAbsent(command))
            val second = SqliteCommandOutboxStorage(context)
            assertEquals(command, second.find(command.key))

            context.openOrCreateDatabase("command-outbox.db", Context.MODE_PRIVATE, null).use { db ->
                db.query("commands", arrayOf("payload"), "key=?", arrayOf(command.key), null, null, null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    val encrypted = cursor.getBlob(0)
                    assertTrue(encrypted.size > payload.toByteArray().size)
                    assertFalse(encrypted.contentEquals(payload.toByteArray()))
                }
            }

            val one = async(Dispatchers.Default) {
                first.transition(command.key, command.id, CommandState.PREPARED, CommandState.POSTED)
            }
            val two = async(Dispatchers.Default) {
                second.transition(command.key, command.id, CommandState.PREPARED, CommandState.POSTED)
            }
            assertEquals(1, listOf(one.await(), two.await()).count { it })
            assertEquals(CommandState.POSTED, second.find(command.key)?.state)
            assertFalse(first.removeIfState(command.key, command.id, CommandState.PREPARED))
            assertTrue(second.removeIfState(command.key, command.id, CommandState.POSTED))
            assertNull(first.find(command.key))
        } finally {
            withContext(NonCancellable) { first.removeHost(hostId) }
        }
    }
}
