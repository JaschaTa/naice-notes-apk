package com.jt.naicenotes.ui.util

import com.jt.naicenotes.data.entity.Item

/**
 * Translates a drag between two rows of the active block into positions in the full list.
 *
 * The list is drawn in two blocks — active rows, then the waiting ones below a divider — so
 * the indices a drag reports are positions in the *rendered* list, while the list being
 * mutated still holds everything in stored order with waiting rows interleaved. Matching by
 * id is what keeps those two from being confused; using the raw index would silently move
 * whichever row happened to sit at that position.
 *
 * Returns null when either end isn't a real active row, which is what a drag targeting the
 * divider or the block below it looks like.
 */
fun reorderWithin(all: List<Item>, active: List<Item>, from: Int, to: Int): Pair<Int, Int>? {
    if (from !in active.indices || to !in active.indices || from == to) return null
    val fromIndex = all.indexOfFirst { it.id == active[from].id }
    val toIndex = all.indexOfFirst { it.id == active[to].id }
    if (fromIndex < 0 || toIndex < 0) return null
    return fromIndex to toIndex
}
