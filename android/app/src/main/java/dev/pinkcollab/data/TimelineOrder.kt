package dev.pinkcollab.data

import java.time.Instant

fun compareTimestamps(a: String, b: String): Int = Instant.parse(a).compareTo(Instant.parse(b))

fun mergeTimeline(snapshot: List<TimelineItem>, live: List<TimelineItem>): List<TimelineItem> =
    (snapshot + live).groupBy { it.id }.values
        .map { group -> group.maxWith { a, b -> compareTimestamps(a.timestamp, b.timestamp) } }
        .sortedWith { a, b -> compareTimestamps(a.timestamp, b.timestamp) }
        .takeLast(500)
