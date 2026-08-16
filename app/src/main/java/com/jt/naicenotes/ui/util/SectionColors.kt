package com.jt.naicenotes.ui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

val SectionColorPalette: List<Color> = listOf(
    Color(0xFF4CAF50), // green
    Color(0xFF2196F3), // blue
    Color(0xFFFF9800), // orange
    Color(0xFFE91E63), // pink
    Color(0xFF9C27B0), // purple
    Color(0xFFF44336), // red
    Color(0xFF009688), // teal
    Color(0xFF795548), // brown
    Color(0xFF607D8B), // blue-grey
)

/** Colour for a newly created section, stored as ARGB on [com.jt.naicenotes.data.entity.Section]. */
fun randomSectionColor(): Int = SectionColorPalette.random().toArgb()

/**
 * Quick-pick emoji offered when naming a section. Deliberately a short, opinionated list rather
 * than a full picker: the field accepts anything the system keyboard can type, so this only has
 * to cover the common cases in one tap.
 */
val SectionEmojiPalette: List<String> = listOf(
    "🛒", "💼", "📥", "📚", "💡", "🏠", "✈️", "🍳",
    "🎁", "💪", "🎬", "🎵", "🧾", "🔧", "🌱", "❤️",
)
