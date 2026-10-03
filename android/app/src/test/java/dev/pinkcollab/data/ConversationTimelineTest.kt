package dev.pinkcollab.data

import org.junit.Assert.*
import org.junit.Test

class ConversationTimelineTest {
    private fun message(id: String, source: String? = null, text: String = id, key: String? = null, streaming: Boolean = false) =
        TimelineItem(id, "assistant", text, if (streaming) "streaming" else "", "", sourceId = source, messageKey = key)

    @Test fun history_replaces_live_final_without_duplicate_or_key_change() {
        val live = message("runtime:msg-1", "message-one", "full reply")
        val saved = message("entry-abc", "message-one", "full reply")
        val output = conversationTimeline(listOf(message("older"), saved), listOf(live, message("newer")))
        assertEquals(listOf("older", live.id, "newer"), output.map { it.id })
        assertEquals("full reply", output[1].text)
    }

    @Test fun identical_text_is_not_an_identity() {
        val first = message("one", "source-one", "same answer")
        val second = message("two", "source-two", "same answer")
        assertEquals(2, conversationTimeline(listOf(first), listOf(second)).size)
    }

    @Test fun persistence_before_message_end_replaces_stream_by_unique_lifecycle_key() {
        val saved = message("saved", "final-source", "complete", "assistant:123")
        val draft = message("live", text = "partial", key = "assistant:123", streaming = true)
        val merged = conversationTimeline(listOf(saved), listOf(draft))
        assertEquals(1, merged.size)
        assertEquals("live", merged.single().id)
        assertEquals("complete", merged.single().text)
    }

    @Test fun ambiguous_lifecycle_timestamps_do_not_erase_messages() {
        val history = listOf(message("one", "one-source", key = "assistant:123"), message("two", "two-source", key = "assistant:123"))
        assertEquals(3, conversationTimeline(history, listOf(message("draft", key = "assistant:123", streaming = true))).size)
    }

    @Test fun live_prefix_is_inserted_before_shared_history_anchor() {
        assertEquals(listOf("old", "live-anchor", "latest"), conversationTimeline(
            listOf(message("saved-anchor", "anchor"), message("latest")),
            listOf(message("old"), message("live-anchor", "anchor")),
        ).map { it.id })
    }

    @Test fun cached_stream_is_replaced_by_newer_delta() {
        val old = message("live", text = "a", streaming = true)
        assertEquals("abc", conversationTimeline(listOf(old), listOf(old.copy(text = "abc"))).single().text)
    }
}
