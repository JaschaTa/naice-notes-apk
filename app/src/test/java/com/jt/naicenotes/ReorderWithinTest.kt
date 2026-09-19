package com.jt.naicenotes

import com.jt.naicenotes.data.entity.Item
import com.jt.naicenotes.ui.util.reorderWithin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The list draws active rows and not-yet-due rows as two blocks, so a drag reports positions
 * in the rendered order while the list being mutated is still in stored order with waiting
 * rows interleaved. Getting this wrong moves whichever row happens to sit at that index —
 * silently, and only for sections that actually hold a scheduled note.
 */
class ReorderWithinTest {

    private fun item(id: Long, dueAt: Long? = null) =
        Item(id = id, sectionId = 1, text = "item $id", position = 0, dueAt = dueAt)

    private val waitingId = 2L

    /** Stored order: 1, 2 (waiting), 3, 4 — so active is 1, 3, 4. */
    private val all = listOf(item(1), item(waitingId, dueAt = FUTURE), item(3), item(4))
    private val active = all.filterNot { it.id == waitingId }

    @Test
    fun `translates rendered indices past an interleaved waiting row`() {
        // Drag the third active row (id 4) to the top: index 2 -> 0 in the rendered list,
        // which is 3 -> 0 in the stored one.
        assertEquals(3 to 0, reorderWithin(all, active, from = 2, to = 0))
    }

    @Test
    fun `an index that maps across the waiting row is still the right item`() {
        // Rendered index 1 is id 3, stored index 2 — not id 2, which is what using the raw
        // index would have moved.
        val (fromIndex, _) = reorderWithin(all, active, from = 1, to = 0)!!
        assertEquals(3L, all[fromIndex].id)
    }

    @Test
    fun `a drag onto the divider or below it is refused`() {
        assertNull(reorderWithin(all, active, from = 0, to = active.size))
        assertNull(reorderWithin(all, active, from = active.size, to = 0))
        assertNull(reorderWithin(all, active, from = 0, to = -1))
    }

    @Test
    fun `a drag that goes nowhere is refused`() {
        assertNull(reorderWithin(all, active, from = 1, to = 1))
    }

    private companion object {
        const val FUTURE = Long.MAX_VALUE
    }
}
