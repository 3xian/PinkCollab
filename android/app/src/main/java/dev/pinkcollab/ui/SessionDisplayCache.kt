package dev.pinkcollab.ui

import dev.pinkcollab.data.SavedHistory
import dev.pinkcollab.data.SessionDetail
import dev.pinkcollab.data.TimelineItem
import dev.pinkcollab.data.conversationTimeline

internal data class SessionDisplay(
    val detail: SessionDetail,
    val historyItems: List<TimelineItem>,
)

/** Cached messages are presentation data, never proof of a successful history request. */
internal fun sessionDetailForDisplay(current: SessionDetail?, cached: SessionDisplay?): SessionDisplay? {
    val detail = current ?: cached?.detail?.copy(
        savedHistory = SavedHistory.Loading,
        snapshotToken = null,
    ) ?: return null
    val items = when (val history = detail.savedHistory) {
        SavedHistory.Loading, SavedHistory.Failed -> cached?.historyItems.orEmpty().filter {
            it.detail != "streaming" && (it.tool == null || it.tool.completed)
        }
        else -> conversationTimeline(history.items, detail.liveItems)
    }
    return SessionDisplay(detail, items)
}
