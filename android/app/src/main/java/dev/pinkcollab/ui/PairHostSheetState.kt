package dev.pinkcollab.ui

import androidx.compose.runtime.saveable.Saver

internal data class PairHostSheetState(
    val visible: Boolean = false,
    val url: String = "",
    val token: String = "",
    val error: String? = null,
    val attemptId: Long = 0,
)

internal val PairHostSheetStateSaver = Saver<PairHostSheetState, List<Any>>(
    save = { listOf(it.visible, it.url, it.token, it.error.orEmpty(), it.attemptId) },
    restore = {
        PairHostSheetState(
            visible = it.getOrNull(0) as? Boolean ?: false,
            url = it.getOrNull(1) as? String ?: "",
            token = it.getOrNull(2) as? String ?: "",
            error = (it.getOrNull(3) as? String).orEmpty().ifEmpty { null },
            attemptId = it.getOrNull(4) as? Long ?: 0,
        )
    },
)
