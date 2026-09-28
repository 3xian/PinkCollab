package dev.pinkcollab.ui

import dev.pinkcollab.data.SavedHistory
import dev.pinkcollab.data.SessionDetail
import dev.pinkcollab.data.TimelineItem

internal data class SessionDisplay(
    val detail: SessionDetail,
    val historyItems: List<TimelineItem>,
)

/** Cached messages are presentation data, never proof of a successful history request. */
internal fun sessionDetailForDisplay(current: SessionDetail?, cached: SessionDisplay?): SessionDisplay? {
    val detail = current ?: cached?.detail?.copy(
        savedHistory = SavedHistory.Loading,
        subscriptionId = null,
        cursor = null,
    ) ?: return null
    val items = when (val history = detail.savedHistory) {
        SavedHistory.Loading, SavedHistory.Failed -> cached?.historyItems.orEmpty()
        else -> history.items
    }
    return SessionDisplay(detail, items)
}
