package com.jt.naicenotes.ui.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
