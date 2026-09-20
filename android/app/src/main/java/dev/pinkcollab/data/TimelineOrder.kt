package dev.pinkcollab.data

import java.time.Instant

/**
 * The gateway keeps at most this many timeline items per session and owns the retention policy.
 * The same number here is only a memory bound for the union of everything a client has merged
 * since its last snapshot, so a long-lived session cannot grow without limit on a phone.
 */
private const val TIMELINE_WINDOW = 500

fun compareTimestamps(a: String, b: String): Int = Instant.parse(a).compareTo(Instant.parse(b))

/**
 * Merges timeline items by id: the newest timestamp wins and [preferred] keeps its position when
 * both sides carry the same one. Ties are reachable because the gateway pins a tool item to the
 * timestamp of the call, so a result update and its start share a value.
 */
fun mergeTimeline(preferred: List<TimelineItem>, incoming: List<TimelineItem>): List<TimelineItem> =
    (preferred + incoming).groupBy { it.id }.values
        .map { group ->
            group.reduce { best, candidate ->
                if (compareTimestamps(candidate.timestamp, best.timestamp) > 0) candidate else best
            }
        }
        .sortedWith { a, b -> compareTimestamps(a.timestamp, b.timestamp) }
        .takeLast(TIMELINE_WINDOW)
