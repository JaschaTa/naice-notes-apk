package com.jt.naicenotes

import com.jt.naicenotes.ui.util.formatDueRelative
import com.jt.naicenotes.ui.util.formatPushedAt
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The send-details dialog is the only place a delivery time is ever shown, and `pushedAt` is
 * epoch millis — an off-by-a-zone reading would be silently plausible rather than obviously
 * wrong, so the conversion is pinned here.
 */
class PushedAtFormatTest {

    @Test
    fun `formats a fixed instant in a fixed zone`() {
        assertEquals(
            "19 Sep 2026, 14:30",
            formatPushedAt(
                epochMillis = BERLIN_AFTERNOON,
                zone = ZoneId.of("Europe/Berlin"),
                locale = Locale.US,
            ),
        )
    }

    @Test
    fun `renders in the requested zone, not UTC`() {
        assertEquals("19 Sep 2026, 12:30", formatPushedAt(BERLIN_AFTERNOON, ZoneId.of("UTC"), Locale.US))
        assertEquals(
            "19 Sep 2026, 14:30",
            formatPushedAt(BERLIN_AFTERNOON, ZoneId.of("Europe/Berlin"), Locale.US),
        )
    }

    @Test
    fun `due dates read in calendar days, not elapsed hours`() {
        val berlin = ZoneId.of("Europe/Berlin")
        // 23:50, so "tomorrow" is ten minutes away in elapsed time but still a day off.
        val lateEvening = java.time.Instant.parse("2026-09-19T21:50:00Z").toEpochMilli()
        val nextMorning = java.time.Instant.parse("2026-09-20T06:00:00Z").toEpochMilli()
        assertEquals("tomorrow", formatDueRelative(nextMorning, lateEvening, berlin))
    }

    @Test
    fun `weeks take over past a fortnight, and overdue counts backwards`() {
        val berlin = ZoneId.of("Europe/Berlin")
        val now = java.time.Instant.parse("2026-09-19T10:00:00Z").toEpochMilli()
        fun atDays(days: Long) = now + days * 24 * 60 * 60 * 1000L
        assertEquals("today", formatDueRelative(now, now, berlin))
        assertEquals("in 12d", formatDueRelative(atDays(12), now, berlin))
        assertEquals("in 3w", formatDueRelative(atDays(21), now, berlin))
        assertEquals("yesterday", formatDueRelative(atDays(-1), now, berlin))
        assertEquals("3d ago", formatDueRelative(atDays(-3), now, berlin))
    }

    private companion object {
        /** 2026-09-19 14:30 in Berlin (summer time, UTC+2). */
        const val BERLIN_AFTERNOON = 1789821000000L
    }
}
