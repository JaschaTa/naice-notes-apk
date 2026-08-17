package com.jt.naicenotes.ui.util

import java.text.BreakIterator

/**
 * The first grapheme cluster of [input], trimmed, or null if there isn't one.
 *
 * A section icon is one visible character — but "one visible character" is neither one `Char` nor
 * one code point. ❤️ is a code point plus a variation selector, 👍🏽 adds a skin-tone modifier, and
 * 👨‍👩‍👧‍👦 is four people joined by zero-width joiners. Clamping to a grapheme cluster keeps
 * whatever the keyboard produced intact, which is what lets the icon field accept every emoji the
 * device can render without this code knowing anything about emoji. Truncating by `Char` or code
 * point instead would silently turn ❤️ into a monochrome ❤ and 👍🏽 into a yellow 👍.
 *
 * Note that grapheme segmentation comes from the platform: Android's ICU-backed `BreakIterator`
 * handles ZWJ sequences, while the plain JDK one used by unit tests is older and splits some of
 * them. Both agree on the single-code-point-plus-modifier cases, which is what the tests assert.
 */
fun firstGrapheme(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val boundaries = BreakIterator.getCharacterInstance()
    boundaries.setText(trimmed)
    val end = boundaries.next()
    if (end == BreakIterator.DONE || end <= 0) return null
    return trimmed.substring(0, end).takeIf { it.isNotBlank() }
}
