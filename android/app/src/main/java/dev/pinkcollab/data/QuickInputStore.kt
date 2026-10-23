package dev.pinkcollab.data

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class QuickInput(val id: String, val text: String)

/** Device-wide snippets never enter the Gateway protocol or session history. */
internal class QuickInputStore(context: Context, preferencesName: String = "quick-inputs") {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    suspend fun read(): List<QuickInput> = withContext(Dispatchers.IO) {
        val raw = preferences.getString("entries", null) ?: return@withContext emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            QuickInput(entry.getString("id"), entry.getString("text"))
        }
    }

    @SuppressLint("UseKtx")
    suspend fun save(entries: List<QuickInput>) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("id", it.id).put("text", it.text)) }
        check(preferences.edit().putString("entries", array.toString()).commit()) { "Could not save quick inputs" }
    }
}
