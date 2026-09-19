package com.jt.naicenotes

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

    private companion object {
        /** 2026-09-19 14:30 in Berlin (summer time, UTC+2). */
        const val BERLIN_AFTERNOON = 1789821000000L
    }
}
