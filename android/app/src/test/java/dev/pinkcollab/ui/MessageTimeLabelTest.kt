package dev.pinkcollab.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class MessageTimeLabelTest {
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-09-27T15:44:00Z")

    @Test
    fun today_shows_only_the_clock_time() {
        assertEquals("15:44", messageTimeLabel("2026-09-27T15:44:00Z", now, zone))
    }

    @Test
    fun another_day_this_year_includes_the_date() {
        assertEquals("9/20 08:05", messageTimeLabel("2026-09-20T08:05:00Z", now, zone))
    }

    @Test
    fun another_year_includes_the_year() {
        assertEquals("2025/12/31 23:01", messageTimeLabel("2025-12-31T23:01:00Z", now, zone))
    }

    @Test
    fun blank_or_unparseable_timestamps_are_omitted() {
        assertEquals("", messageTimeLabel("", now, zone))
        assertEquals("", messageTimeLabel("not-a-time", now, zone))
    }
}
