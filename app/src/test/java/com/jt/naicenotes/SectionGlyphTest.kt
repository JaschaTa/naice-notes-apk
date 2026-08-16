package com.jt.naicenotes

import com.jt.naicenotes.data.entity.Section
import com.jt.naicenotes.ui.util.SectionEmojiPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `Section.glyph` is drawn in four places — the rail tile, the header toggle, the move-to-section
 * picker and the widget's `renderTile` — and `hasEmoji` decides the styling in every one of them
 * (light wash behind an emoji, white-on-solid behind a letter). A wrong answer here is wrong
 * everywhere at once, including inside RemoteViews where it can't be debugged.
 */
class SectionGlyphTest {

    private fun section(name: String, emoji: String? = null) =
        Section(name = name, color = 0xFF4CAF50.toInt(), position = 0, emoji = emoji)

    @Test
    fun `an emoji is used verbatim`() {
        val s = section(name = "Shopping", emoji = "🛒")
        assertEquals("🛒", s.glyph)
        assertTrue(s.hasEmoji)
    }

    @Test
    fun `without an emoji the name's initial is used, uppercased`() {
        val s = section(name = "shopping")
        assertEquals("S", s.glyph)
        assertFalse(s.hasEmoji)
    }

    @Test
    fun `a blank emoji counts as no emoji`() {
        // Empty rather than null is what an emoji picker cleared by hand tends to store, and
        // it must not render as an invisible tile.
        listOf("", " ", "\n").forEach { blank ->
            val s = section(name = "Work", emoji = blank)
            assertFalse("hasEmoji for '$blank'", s.hasEmoji)
            assertEquals("glyph for '$blank'", "W", s.glyph)
        }
    }

    @Test
    fun `leading whitespace in the name is ignored`() {
        assertEquals("F", section(name = "  food").glyph)
    }

    @Test
    fun `a nameless section still yields something drawable`() {
        // Room has no NOT-EMPTY constraint on name, so this has to degrade rather than crash.
        assertEquals("•", section(name = "").glyph)
        assertEquals("•", section(name = "   ").glyph)
    }

    @Test
    fun `non-ASCII initials survive`() {
        assertEquals("Ü", section(name = "übersicht").glyph)
        assertEquals("É", section(name = "études").glyph)
    }

    @Test
    fun `multi-codepoint emoji are not truncated`() {
        // ❤️ is U+2764 plus a variation selector; keeping both matters because the widget
        // hands this straight to setTextViewText, where a lone U+2764 renders monochrome.
        val heart = section(name = "Health", emoji = "❤️")
        assertEquals("❤️", heart.glyph)
        assertEquals(2, heart.glyph.length)
    }

    @Test
    fun `every palette emoji is usable`() {
        assertTrue("palette is not empty", SectionEmojiPalette.isNotEmpty())
        assertEquals("no duplicates", SectionEmojiPalette.size, SectionEmojiPalette.toSet().size)
        SectionEmojiPalette.forEach { emoji ->
            val s = section(name = "Any", emoji = emoji)
            assertTrue("'$emoji' should count as an emoji", s.hasEmoji)
            assertEquals("'$emoji' should render itself", emoji, s.glyph)
        }
    }

    @Test
    fun `glyph is never blank`() {
        listOf("", "  ", "x", "Ω", "123", "🛒 cart").forEach { name ->
            listOf(null, "", "🌱").forEach { emoji ->
                val glyph = section(name = name, emoji = emoji).glyph
                assertTrue("blank glyph for name='$name' emoji='$emoji'", glyph.isNotBlank())
            }
        }
    }
}
