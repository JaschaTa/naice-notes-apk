package com.jt.naicenotes.data.util

import com.jt.naicenotes.data.db.SectionItemBucket
import java.time.Instant
import java.time.ZoneId

/** Open notes in a section, and how many of those have come due. */
data class SectionCounts(val open: Int, val due: Int)

/** What the clear dialog needs to name its two options. */
data class SectionTotals(val checked: Int, val total: Int)

/**
 * Folds the item buckets into per-section open/due counts at [now].
 *
 * The query can't do this itself: it would need `now` as a parameter, and a Room Flow built
 * with a bound argument keeps returning results for the value it was created with. Grouping
 * on `dueAt` instead keeps the aggregate time-independent, and the clock is applied here.
 *
 * A section with nothing open — because it's empty, all done, or all still waiting — is absent
 * from the result rather than present with zero, which is what the badge already expects.
 */
fun countsBySection(buckets: List<SectionItemBucket>, now: Long): Map<Long, SectionCounts> {
    val counts = mutableMapOf<Long, SectionCounts>()
    buckets.forEach { bucket ->
        if (bucket.isChecked) return@forEach
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
 * Checked and overall note counts per section. Unlike [countsBySection] this ignores the clock
 * entirely — clearing a section removes what's stored, whether or not it's come due yet.
 */
fun totalsBySection(buckets: List<SectionItemBucket>): Map<Long, SectionTotals> {
    val totals = mutableMapOf<Long, SectionTotals>()
    buckets.forEach { bucket ->
        val current = totals[bucket.sectionId] ?: SectionTotals(checked = 0, total = 0)
        totals[bucket.sectionId] = SectionTotals(
            checked = current.checked + if (bucket.isChecked) bucket.count else 0,
            total = current.total + bucket.count,
        )
    }
    return totals
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
