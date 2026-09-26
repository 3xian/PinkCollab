package dev.pinkcollab.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class SelectedFile(val uri: String, val name: String, val id: String)

internal suspend fun selectedFile(context: Context, uri: Uri): SelectedFile = withContext(Dispatchers.IO) {
    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val original = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull().orEmpty()
    var name = original.ifBlank { "attachment" }
        .map { if (Character.isISOControl(it) || it in "/\\<>:\"|?*") '_' else it }
        .joinToString("")
        .trimEnd(' ', '.')
    val stem = name.substringBefore('.').uppercase()
    val reserved = stem in setOf("CON", "PRN", "AUX", "NUL") ||
        (stem.length == 4 && stem.take(3) in setOf("COM", "LPT") && stem[3] in '1'..'9')
    if (reserved) name = "file_$name"
    while (name.toByteArray(Charsets.UTF_8).size > 120) name = name.dropLast(1)
    name = name.trimEnd(' ', '.')
    SelectedFile(uri.toString(), name.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "attachment", "file_" + UUID.randomUUID().toString().replace("-", ""))
}
