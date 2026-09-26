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
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CommandOutboxStorageDeviceTest {
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
