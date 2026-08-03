package com.jt.naicenotes

import androidx.compose.ui.graphics.toArgb
import com.jt.naicenotes.ui.util.SectionColorPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section colours are persisted as ARGB ints, so the palette must convert to exactly the
 * literals it was declared with. This previously went through a hand-rolled `toArgb`
 * shadowing the framework one; the test locks the equivalence that made replacing it safe.
 */
class SectionColorTest {

    @Test
    fun `every palette colour round-trips to its own literal`() {
        val expected = listOf(
            0xFF4CAF50, 0xFF2196F3, 0xFFFF9800, 0xFFE91E63, 0xFF9C27B0,
            0xFFF44336, 0xFF009688, 0xFF795548, 0xFF607D8B,
        ).map { it.toInt() }

        assertEquals(expected.size, SectionColorPalette.size)
        SectionColorPalette.forEachIndexed { index, color ->
            assertEquals("palette[$index]", expected[index], color.toArgb())
        }
    }

    @Test
    fun `palette colours are all fully opaque and distinct`() {
        val argbs = SectionColorPalette.map { it.toArgb() }
        assertEquals("no duplicates", argbs.size, argbs.toSet().size)
        assertTrue("all opaque", argbs.all { (it ushr 24) == 0xFF })
    }
}
