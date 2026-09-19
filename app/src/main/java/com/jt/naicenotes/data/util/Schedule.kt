package com.jt.naicenotes.data.util

import com.jt.naicenotes.data.db.SectionDueBucket
import java.time.Instant
import java.time.ZoneId

/** Open notes in a section, and how many of those have come due. */
data class SectionCounts(val open: Int, val due: Int)

/**
 * Folds the open-note buckets into per-section counts at [now].
 *
 * The query can't do this itself: it would need `now` as a parameter, and a Room Flow built
 * with a bound argument keeps returning results for the value it was created with. Grouping
 * on `dueAt` instead keeps the aggregate time-independent, and the clock is applied here.
 *
 * A section whose notes are all still waiting is absent from the result rather than present
 * with zero — same convention as the badge query it replaced.
 */
fun countsBySection(buckets: List<SectionDueBucket>, now: Long): Map<Long, SectionCounts> {
    val counts = mutableMapOf<Long, SectionCounts>()
    buckets.forEach { bucket ->
        val dueAt = bucket.dueAt
        if (dueAt != null && dueAt > now) return@forEach
        val current = counts[bucket.sectionId] ?: SectionCounts(open = 0, due = 0)
        counts[bucket.sectionId] = SectionCounts(
            open = current.open + bucket.count,
            due = current.due + if (dueAt != null) bucket.count else 0,
        )
    }
    return counts
}

/**
 * When a repeating note is next owed, counted in calendar weeks from [from].
 *
 * Deliberately not `weeks * 7 * 24 * 60 * 60 * 1000`: across a DST boundary that lands an
 * hour off, and a fortnightly chore drifts an hour twice a year until it crosses midnight
 * and lands on the wrong day.
 */
fun nextDueAt(from: Long, repeatWeeks: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(from)
        .atZone(zone)
        .plusWeeks(repeatWeeks.toLong())
        .toInstant()
        .toEpochMilli()

/**
 * Reinterprets a `DatePicker` selection as local midnight.
 *
 * The picker reports the chosen day as UTC midnight, so east of Greenwich the raw value is
 * the evening before — the note would come due a day early.
 */
fun utcDateToLocalStartOfDay(utcMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(utcMillis)
        .atZone(ZoneId.of("UTC"))
        .toLocalDate()
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()

/**
 * Midnight [days] days after [from], for the composer's quick picks — "in 2 weeks" means that
 * day, not that hour of it, so a note set at 23:50 doesn't come due late the following evening.
 */
fun startOfDayIn(from: Long, days: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(from)
        .atZone(zone)
        .plusDays(days)
        .toLocalDate()
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()
