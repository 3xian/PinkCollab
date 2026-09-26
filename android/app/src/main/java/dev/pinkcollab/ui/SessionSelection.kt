package dev.pinkcollab.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver

internal fun SessionKey?.savedSelection(): List<String> =
    if (this == null) emptyList() else listOf(hostId, sessionId)

internal fun restoreSelectedSession(saved: List<String>): SessionKey? =
    if (saved.size == 2 && saved.all { it.isNotBlank() }) SessionKey(saved[0], saved[1]) else null

internal val SelectedSessionSaver = Saver<MutableState<SessionKey?>, List<String>>(
    save = { it.value.savedSelection() },
    restore = { mutableStateOf(restoreSelectedSession(it)) },
)
