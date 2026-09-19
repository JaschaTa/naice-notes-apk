package com.jt.naicenotes.ui.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private const val PUSHED_AT_PATTERN = "d MMM yyyy, HH:mm"

/**
 * When a note reached the Claude inbox, for the send-details dialog. Zone and locale are
 * parameters so the format is pinnable in a test — read from the device otherwise.
 */
fun formatPushedAt(
    epochMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = DateTimeFormatter.ofPattern(PUSHED_AT_PATTERN, locale)
    .format(Instant.ofEpochMilli(epochMillis).atZone(zone))

/**
 * How far off a due date is, for the waiting row's chip and the schedule tooltip.
 *
 * Counts whole calendar days between the two dates, not elapsed hours: something due tomorrow
 * morning reads "tomorrow" whether it's set at breakfast or at midnight. Weeks take over past
 * a fortnight, where "in 23d" stops meaning much. Goes backwards too — a note can sit due for
 * days before anyone opens the app.
 */
fun formatDueRelative(dueAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val due = Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(today, due)
    return when {
        days == 0L -> "today"
        days == 1L -> "tomorrow"
        days == -1L -> "yesterday"
        days < -1L -> "${-days}d ago"
        days <= 13L -> "in ${days}d"
        else -> "in ${days / 7}w"
    }
}

/** The due date itself, for the tooltip body. Date only — a due date has no useful time of day. */
fun formatDueDate(
    dueAt: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
    .format(Instant.ofEpochMilli(dueAt).atZone(zone))
