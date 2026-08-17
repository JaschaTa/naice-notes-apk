package com.jt.naicenotes

import com.jt.naicenotes.ui.util.firstGrapheme
import com.jt.naicenotes.ui.util.nextIconInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The icon field accepts anything the system keyboard produces and keeps the first grapheme
 * cluster. Truncating by `Char` or code point instead would silently downgrade the emoji — ❤️ to a
 * monochrome ❤, 👍🏽 to a yellow 👍 — which is exactly the bug this clamp exists to prevent.
 */
class GraphemeTest {

    @Test
    fun `a single emoji is kept whole`() {
        assertEquals("🛒", firstGrapheme("🛒"))
    }

    @Test
    fun `a variation selector rides along`() {
        // ❤️ is U+2764 + U+FE0F. Dropping the selector renders the monochrome glyph.
        val heart = "❤️"
        assertEquals(2, heart.length)
        assertEquals(heart, firstGrapheme(heart))
    }

    @Test
    fun `a skin-tone modifier rides along`() {
        // 👍🏽 is a thumbs-up plus U+1F3FD. Dropping it renders the default yellow.
        val thumb = "👍🏽"
        assertEquals(thumb, firstGrapheme(thumb))
    }

    @Test
    fun `only the first emoji survives a longer string`() {
        assertEquals("🛒", firstGrapheme("🛒🍳🌱"))
        assertEquals("🛒", firstGrapheme("🛒 shopping"))
    }

    @Test
    fun `plain characters work too`() {
        // Not restricted to emoji on purpose — a symbol or letter is a legitimate icon.
        assertEquals("★", firstGrapheme("★"))
        assertEquals("A", firstGrapheme("Abc"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals("🛒", firstGrapheme("  🛒  "))
        assertEquals("A", firstGrapheme("\n A"))
    }

    @Test
    fun `nothing usable yields null`() {
        // Null is what clears the icon, so an emptied field must land here rather than on "".
        assertNull(firstGrapheme(""))
        assertNull(firstGrapheme("   "))
        assertNull(firstGrapheme("\n\t"))
    }

    @Test
    fun `tapping a new emoji over an existing one replaces it`() {
        // The regression this exists for: a text field appends, so the emoji keyboard hands us
        // "🛒🍳". Reading from the front kept 🛒 and the keyboard looked broken.
        assertEquals("🍳", nextIconInput("🛒", "🛒🍳"))
        assertEquals("X", nextIconInput("🛒", "🛒X"))
        assertEquals("👍🏽", nextIconInput("❤️", "❤️👍🏽"))
    }

    @Test
    fun `typing into an empty field takes the first character`() {
        assertEquals("🛒", nextIconInput(null, "🛒"))
        assertEquals("🛒", nextIconInput("", "🛒 shopping"))
    }

    @Test
    fun `replacing a selection reads from the front`() {
        // Not an append — the old value is gone, so there's no "new part" to prefer.
        assertEquals("🍳", nextIconInput("🛒", "🍳"))
        assertEquals("🍳", nextIconInput("🛒", "🍳🛒"))
    }

    @Test
    fun `emptying the field clears the icon`() {
        assertNull(nextIconInput("🛒", ""))
        assertNull(nextIconInput("🛒", "   "))
    }

    @Test
    fun `the result is never longer than what went in`() {
        listOf("🛒", "❤️", "👍🏽", "A", "★", "🛒🍳", " x ").forEach { input ->
            val out = firstGrapheme(input)!!
            assert(out.length <= input.trim().length) { "grew for '$input'" }
        }
    }
}
