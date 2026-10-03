package dev.pinkcollab.data

/** History defines branch order; shared source identities stitch the live tail into it.
 * Never match message text or sort a transcript by wall-clock timestamps. */
fun conversationTimeline(history: List<TimelineItem>, live: List<TimelineItem>): List<TimelineItem> {
    fun identity(item: TimelineItem) = item.sourceId ?: item.tool?.let { "tool:${it.callId}" } ?: item.id
    val savedByMessageKey = history.filter { it.messageKey != null }.groupBy { it.messageKey }
    val liveByMessageKey = live.filter { it.messageKey != null }.groupBy { it.messageKey }
    val tail = live.map { item ->
        val saved = savedByMessageKey[item.messageKey]?.singleOrNull()
        // Persistence can win the race against message_end. Only bridge a unique
        // lifecycle timestamp; ambiguous timestamps never erase an actual message.
        if (saved != null && liveByMessageKey[item.messageKey]?.size == 1)
            item.copy(sourceId = saved.sourceId) else item
    }.distinctBy(::identity)
    val tailByIdentity = tail.associateBy(::identity)
    val output = history.distinctBy(::identity).map { saved ->
        tailByIdentity[identity(saved)]?.let { current ->
            // Final persisted content supersedes a preview while retaining its Compose key.
            if (saved.sourceId == null ||
                (saved.tool != null && !saved.tool.completed)) current
            else saved.copy(id = current.id)
        } ?: saved
    }.toMutableList()
    val present = output.mapTo(mutableSetOf(), ::identity)
    tail.forEachIndexed { index, item ->
        if (present.add(identity(item))) {
            val nextAnchor = tail.asSequence().drop(index + 1).map(::identity).firstOrNull { it in present }
            val before = nextAnchor?.let { anchor -> output.indexOfFirst { identity(it) == anchor } } ?: -1
            if (before >= 0) output.add(before, item) else output.add(item)
        }
    }
    return output
}
