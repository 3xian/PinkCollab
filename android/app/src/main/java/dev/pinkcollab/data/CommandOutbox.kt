package dev.pinkcollab.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal enum class CommandState { PREPARED, POSTED, LOOKUP_ONLY }

internal data class PendingCommand(
    val key: String,
    val hostId: String,
    val clientId: String,
    val sessionId: String,
    val id: String,
    val type: String,
    val payload: String,
    val state: CommandState = CommandState.PREPARED,
    val createdAt: Long = System.currentTimeMillis(),
)

internal interface CommandOutboxStorage {
    suspend fun find(key: String): PendingCommand?
    suspend fun insertIfAbsent(command: PendingCommand): PendingCommand
    suspend fun transition(key: String, id: String, from: CommandState, to: CommandState): Boolean
    suspend fun remove(key: String, id: String)
    suspend fun removeIfState(key: String, id: String, state: CommandState): Boolean
    suspend fun records(): List<PendingCommand>
    suspend fun promptsFor(hostId: String, clientId: String, sessionId: String): List<PendingCommand>
    suspend fun removeHost(hostId: String)
}

/** Each claim is one durable, conditional SQLite update. A deleted draft cannot be posted later. */
internal class SqliteCommandOutboxStorage(context: Context) : CommandOutboxStorage {
    private val helper = object : SQLiteOpenHelper(context.applicationContext, "command-outbox.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE commands (
                key TEXT PRIMARY KEY NOT NULL, host_id TEXT NOT NULL, client_id TEXT NOT NULL,
                session_id TEXT NOT NULL, command_id TEXT NOT NULL, type TEXT NOT NULL,
                payload BLOB NOT NULL, state TEXT NOT NULL, created_at INTEGER NOT NULL
            )""")
            db.execSQL("CREATE INDEX commands_session ON commands(host_id,client_id,session_id,type)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    private val alias = "pinkcollab.command-outbox.v2"


    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun encrypt(payload: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        return cipher.iv + cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
    }

    private fun decrypt(bytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    private fun Cursor.command() = PendingCommand(
        key = getString(getColumnIndexOrThrow("key")),
        hostId = getString(getColumnIndexOrThrow("host_id")),
        clientId = getString(getColumnIndexOrThrow("client_id")),
        sessionId = getString(getColumnIndexOrThrow("session_id")),
        id = getString(getColumnIndexOrThrow("command_id")),
        type = getString(getColumnIndexOrThrow("type")),
        payload = decrypt(getBlob(getColumnIndexOrThrow("payload"))),
        state = CommandState.valueOf(getString(getColumnIndexOrThrow("state"))),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
    )

    private fun findIn(db: SQLiteDatabase, key: String): PendingCommand? =
        db.query("commands", null, "key=?", arrayOf(key), null, null, null).use { if (it.moveToFirst()) it.command() else null }

    override suspend fun find(key: String): PendingCommand? = withContext(Dispatchers.IO) {
        findIn(helper.readableDatabase, key)
    }

    override suspend fun insertIfAbsent(command: PendingCommand): PendingCommand = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("key", command.key); put("host_id", command.hostId); put("client_id", command.clientId)
            put("session_id", command.sessionId); put("command_id", command.id); put("type", command.type)
            put("payload", encrypt(command.payload)); put("state", command.state.name); put("created_at", command.createdAt)
        }
        db.insertWithOnConflict("commands", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        checkNotNull(findIn(db, command.key)) { "Command outbox insertion failed" }
    }

    override suspend fun transition(key: String, id: String, from: CommandState, to: CommandState): Boolean = withContext(Dispatchers.IO) {
        helper.writableDatabase.update("commands", ContentValues().apply { put("state", to.name) },
            "key=? AND command_id=? AND state=?", arrayOf(key, id, from.name)) == 1
    }

    override suspend fun remove(key: String, id: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("commands", "key=? AND command_id=?", arrayOf(key, id))
        Unit
    }

    override suspend fun removeIfState(key: String, id: String, state: CommandState): Boolean = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("commands", "key=? AND command_id=? AND state=?", arrayOf(key, id, state.name)) == 1
    }

    override suspend fun records(): List<PendingCommand> = withContext(Dispatchers.IO) {
        helper.readableDatabase.query("commands", null, null, null, null, null, "created_at").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.command()) }
        }
    }

    override suspend fun promptsFor(hostId: String, clientId: String, sessionId: String): List<PendingCommand> = withContext(Dispatchers.IO) {
        helper.readableDatabase.query("commands", null,
            "host_id=? AND client_id=? AND session_id=? AND type=?",
            arrayOf(hostId, clientId, sessionId, "prompt"), null, null, "created_at").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.command()) }
        }
    }

    override suspend fun removeHost(hostId: String) = withContext(Dispatchers.IO) {
        helper.writableDatabase.delete("commands", "host_id=?", arrayOf(hostId))
        Unit
    }
}

internal data class CommandRequest(
    val hostId: String,
    val clientId: String,
    val sessionId: String,
    val action: String,
    val type: String,
    val intentId: String? = null,
    val fields: () -> JSONObject,
) {
    val key: String get() = MessageDigest.getInstance("SHA-256")
        .digest(JSONObject().put("host", hostId).put("client", clientId).put("session", sessionId)
            .put("action", action).toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal data class CommandTransport(
    val post: suspend (JSONObject) -> JSONObject,
    val lookup: suspend (String) -> JSONObject,
)

internal class TerminalCommandFailure(message: String) : IOException(message)

/** Owns preparation, receipt lookup, dispatch, and settlement for every command type. */
internal class DurableCommandOutbox(private val storage: CommandOutboxStorage) {
    suspend fun removeHost(hostId: String) = storage.removeHost(hostId)
    suspend fun records(): List<PendingCommand> = storage.records()

    suspend fun submit(request: CommandRequest, transport: CommandTransport) {
        if (request.type == "prompt") {
            require(!request.intentId.isNullOrBlank()) { "Prompt intent ID required" }
            settleEarlierPrompts(request, transport.lookup)
            // A restored draft can outlive its local row. Its stable intent ID is also the Gateway ID.
            if (storage.find(request.key) == null) {
                lookupReceipt(transport.lookup, request.intentId)?.let { checkReceipt(it, request.intentId); return }
            }
        }
        val commandId = request.intentId ?: UUID.randomUUID().toString()
        val pending = storage.insertIfAbsent(PendingCommand(
            key = request.key, hostId = request.hostId, clientId = request.clientId,
            sessionId = request.sessionId, id = commandId, type = request.type,
            payload = JSONObject(request.fields().toString()).put("type", request.type)
                .put("commandId", commandId).toString(),
        ))
        execute(pending, transport)
    }

    suspend fun recover(pending: PendingCommand, transport: CommandTransport) {
        execute(pending, transport)
    }

    private suspend fun execute(snapshot: PendingCommand, transport: CommandTransport) {
        var pending = storage.find(snapshot.key)?.takeIf { it.id == snapshot.id } ?: return
        if (pending.state != CommandState.PREPARED) {
            val known = lookupReceipt(transport.lookup, pending.id)
            if (known != null) {
                settle(pending, known)
                return
            }
            if (pending.state == CommandState.LOOKUP_ONLY) {
                throw IOException("Command ${pending.id} has an unconfirmed outcome; inspect the session before retrying")
            }
        } else {
            val claimed = storage.transition(pending.key, pending.id, CommandState.PREPARED,
                if (pending.type == "stop_runtime") CommandState.LOOKUP_ONLY else CommandState.POSTED)
            if (!claimed) {
                storage.find(pending.key)?.takeIf { it.id == pending.id }?.let { execute(it, transport) }
                return
            }
        }
        // The conditional claim above is the last possible point at which an unsent row can be deleted.
        pending = storage.find(pending.key)?.takeIf { it.id == pending.id } ?: return
        val response = try {
            transport.post(JSONObject(pending.payload))
        } catch (failure: IOException) {
            val known = lookupReceipt(transport.lookup, pending.id)
            if (known != null) {
                settle(pending, known)
                return
            }
            if (failure is GatewayHttpException && failure.statusCode in 400..499 &&
                failure.statusCode != 408 && failure.statusCode != 429) storage.remove(pending.key, pending.id)
            throw IOException("Command ${pending.id} was not confirmed; retry to check its receipt", failure)
        }
        if (!response.optBoolean("receiptStored", true)) {
            storage.transition(pending.key, pending.id, CommandState.POSTED, CommandState.LOOKUP_ONLY)
            throw IOException("Stop sent, but its receipt could not be saved; verify the runtime state")
        }
        settle(pending, response.getJSONObject("operation"))
    }

    private suspend fun settleEarlierPrompts(request: CommandRequest, lookup: suspend (String) -> JSONObject) {
        for (saved in storage.promptsFor(request.hostId, request.clientId, request.sessionId)) {
            if (saved.key == request.key) continue
            if (saved.state == CommandState.PREPARED &&
                storage.removeIfState(saved.key, saved.id, CommandState.PREPARED)) continue
            val current = storage.find(saved.key)?.takeIf { it.id == saved.id } ?: continue
            val receipt = lookupReceipt(lookup, current.id)
                ?: throw IOException("Earlier prompt ${current.id} is unconfirmed. Retry that draft before sending another prompt.")
            val status = receipt.getString("status")
            if (status == "outcome_unknown") {
                storage.transition(current.key, current.id, CommandState.POSTED, CommandState.LOOKUP_ONLY)
                throw IOException("Earlier prompt ${current.id} has an unconfirmed outcome")
            }
            settle(current, receipt, allowTerminalFailure = true)
        }
    }

    private suspend fun settle(pending: PendingCommand, receipt: JSONObject, allowTerminalFailure: Boolean = false) {
        require(receipt.getString("commandId") == pending.id) { "Gateway returned a different command ID" }
        when (receipt.getString("status")) {
            "succeeded", "failed", "cancelled" -> storage.remove(pending.key, pending.id)
            "outcome_unknown" -> storage.transition(pending.key, pending.id, CommandState.POSTED, CommandState.LOOKUP_ONLY)
            "accepted", "dispatching", "running" -> Unit
            else -> throw IOException("Unknown command receipt status")
        }
        if (!allowTerminalFailure) checkReceipt(receipt, pending.id)
    }
}

private suspend fun lookupReceipt(lookup: suspend (String) -> JSONObject, id: String): JSONObject? = try {
    lookup(id)
} catch (failure: CancellationException) {
    throw failure
} catch (failure: GatewayHttpException) {
    if (failure.statusCode == 404 && failure.errorCode == "operation_not_found") null else throw failure
}

private fun checkReceipt(receipt: JSONObject, id: String) {
    require(receipt.getString("commandId") == id) { "Gateway returned a different command ID" }
    when (receipt.getString("status")) {
        "failed", "cancelled" -> throw TerminalCommandFailure(receipt.optJSONObject("error")?.optString("message")
            ?.takeIf { it.isNotBlank() } ?: "Command ${receipt.getString("status")}")
        "outcome_unknown" -> throw IOException("Command $id has an unconfirmed outcome; inspect the session before sending another command")
        "accepted", "dispatching", "running", "succeeded" -> Unit
        else -> throw IOException("Unknown command receipt status")
    }
}
