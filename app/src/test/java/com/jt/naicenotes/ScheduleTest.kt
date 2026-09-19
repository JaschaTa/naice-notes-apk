package com.jt.naicenotes

import com.jt.naicenotes.data.db.SectionDueBucket
import com.jt.naicenotes.data.entity.Item
import com.jt.naicenotes.data.util.countsBySection
import com.jt.naicenotes.data.util.nextDueAt
import com.jt.naicenotes.data.util.startOfDayIn
import com.jt.naicenotes.data.util.utcDateToLocalStartOfDay
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The schedule's arithmetic and the counts fold. Both are places where being quietly wrong
 * looks exactly like being right: a badge off by one reads as a miscount, and a repeat that
 * drifts an hour only becomes visible months later when it lands on the wrong day.
 */
class ScheduleTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun item(dueAt: Long? = null, repeatWeeks: Int? = null) =
        Item(id = 1, sectionId = 1, text = "t", position = 0, dueAt = dueAt, repeatWeeks = repeatWeeks)

    // ---- Item state ----

    @Test
    fun `an unscheduled item is neither due nor waiting`() {
        val plain = item()
        assertFalse(plain.isDue(NOW))
        assertFalse(plain.isWaiting(NOW))
        assertFalse(plain.isScheduled)
    }

    @Test
    fun `a due date exactly now counts as due, not waiting`() {
        val edge = item(dueAt = NOW)
        assertTrue(edge.isDue(NOW))
        assertFalse(edge.isWaiting(NOW))
    }

    @Test
    fun `a repeat with no due date is scheduled but not waiting`() {
        val recurring = item(repeatWeeks = 2)
        assertTrue(recurring.isScheduled)
        assertTrue(recurring.isRecurring)
        assertFalse(recurring.isWaiting(NOW))
    }

    // ---- countsBySection ----

    @Test
    fun `unscheduled and due items both count as open, only the dated ones as due`() {
        val counts = countsBySection(
            listOf(
                SectionDueBucket(sectionId = 1, dueAt = null, count = 3),
                SectionDueBucket(sectionId = 1, dueAt = NOW - 1, count = 2),
            ),
            now = NOW,
        )
        assertEquals(5, counts.getValue(1).open)
        assertEquals(2, counts.getValue(1).due)
    }

    @Test
    fun `waiting items are excluded from both counts`() {
        val counts = countsBySection(
            listOf(
                SectionDueBucket(sectionId = 1, dueAt = null, count = 1),
                SectionDueBucket(sectionId = 1, dueAt = NOW + 1, count = 4),
            ),
            now = NOW,
        )
        assertEquals(1, counts.getValue(1).open)
        assertEquals(0, counts.getValue(1).due)
    }

    @Test
    fun `a section with nothing but waiting items is absent rather than zero`() {
        val counts = countsBySection(
            listOf(SectionDueBucket(sectionId = 7, dueAt = NOW + 1, count = 2)),
            now = NOW,
        )
        assertNull(counts[7])
    }

    // ---- nextDueAt ----

    @Test
    fun `a repeat crossing the DST boundary keeps its wall-clock time`() {
        // 2026-10-20 09:00 Berlin, +2 weeks lands on 2026-11-03, after the clocks go back.
        val start = Instant.parse("2026-10-20T07:00:00Z").toEpochMilli()
        val next = nextDueAt(start, repeatWeeks = 2, zone = berlin)
        val local = Instant.ofEpochMilli(next).atZone(berlin)
        assertEquals("2026-11-03T09:00", local.toLocalDateTime().toString())
        // Plain millisecond arithmetic would land an hour out.
        assertTrue(next != start + 2 * 7 * 24 * 60 * 60 * 1000L)
    }

    // ---- start of day ----

    @Test
    fun `a quick pick lands on midnight of the target day, not the current time of day`() {
        val lateEvening = Instant.parse("2026-09-19T21:40:00Z").toEpochMilli()
        val inTwoWeeks = startOfDayIn(lateEvening, days = 14, zone = berlin)
        val local = Instant.ofEpochMilli(inTwoWeeks).atZone(berlin)
        assertEquals("2026-10-03T00:00", local.toLocalDateTime().toString())
    }

    @Test
    fun `a picked date is read as local midnight, not UTC midnight`() {
        // The picker reports UTC midnight; east of Greenwich the raw value is the evening
        // before, which would make the note come due a day early.
        val utcMidnight = Instant.parse("2026-10-03T00:00:00Z").toEpochMilli()
        val local = Instant.ofEpochMilli(utcDateToLocalStartOfDay(utcMidnight, berlin))
            .atZone(berlin)
        assertEquals("2026-10-03T00:00", local.toLocalDateTime().toString())
    }

    private companion object {
        /** 2026-09-19 12:00 UTC. */
        const val NOW = 1789819200000L
    }
}
