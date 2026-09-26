package dev.pinkcollab.data

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Gateway credentials are encrypted at rest; OMP/provider credentials never reach the app.
interface PairedHostStore {
    suspend fun read(): List<PairedHost>
    suspend fun save(hosts: List<PairedHost>)
}

class CredentialStore(context: Context, private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : PairedHostStore {
    private val appContext = context.applicationContext
    private val prefs by lazy { appContext.getSharedPreferences("paired-hosts", Context.MODE_PRIVATE) }
    private val alias = "pinkcollab.hosts.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override suspend fun read(): List<PairedHost> = withContext(ioDispatcher) {
        val raw = prefs.getString("encrypted", null) ?: return@withContext emptyList()
        val bytes = Base64.decode(raw, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        JSONArray(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)).objects().map {
            PairedHost(it.getJSONObject("host").host(), it.getString("url"), it.getString("credential"), it.getString("clientId"))
        }
    }
    // Check commit's Boolean result so a failed durable write is reported to the caller.
    @SuppressLint("UseKtx")
    override suspend fun save(hosts: List<PairedHost>) = withContext(ioDispatcher) {
        val array = JSONArray()
        hosts.forEach { array.put(JSONObject().put("host", it.host.json()).put("url", it.url).put("credential", it.credential).put("clientId", it.clientId)) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(array.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("encrypted", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) { "Cannot save paired hosts" }
    }
}
