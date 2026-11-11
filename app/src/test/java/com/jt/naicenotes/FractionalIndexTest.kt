package com.jt.naicenotes

import com.jt.naicenotes.data.sync.FractionalIndex
import com.jt.naicenotes.data.sync.LegacyUuid
import com.jt.naicenotes.data.sync.rekeyForOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every expected value here was produced by the JS packages the PWA runs (fractional-indexing
 * 4.0.0, and the uuid5 in import-android-snapshot.mjs). If the Kotlin port drifts, the PWA
 * rejects Android's keys or the first sync uploads duplicates — so these are byte comparisons,
 * not property checks.
 */
class FractionalIndexTest {

    @Test
    fun keyBetweenMatchesJs() {
        val cases = listOf(
            Triple(null, null, "a0"),
            Triple(null, "a0", "Zz"),
            Triple("a0", null, "a1"),
            Triple("a0", "a1", "a0V"),
            Triple("a0", "a0V", "a0G"),
            Triple("a1", "a2", "a1V"),
            Triple("Zz", "a0", "ZzV"),
            Triple("a0", "b00", "a1"),
            Triple("a0V", "a1", "a0l"),
            Triple("Zy", "Zz", "ZyV"),
            Triple("a4", "a5", "a4V"),
            Triple(null, "Zz", "Zy"),
            Triple("zzzzzzzzzzzzzzzzzzzzzzzzzzz", null, "zzzzzzzzzzzzzzzzzzzzzzzzzzzV"),
        )
        for ((a, b, expected) in cases) {
            assertEquals("keyBetween($a, $b)", expected, FractionalIndex.keyBetween(a, b))
        }
    }

    @Test
    fun nKeysBetweenMatchesJs() {
        assertEquals("a0,a1,a2,a3,a4", FractionalIndex.nKeysBetween(null, null, 5).joinToString(","))
        assertEquals("Zw,Zx,Zy,Zz", FractionalIndex.nKeysBetween(null, "a0", 4).joinToString(","))
        assertEquals("a08,a0G,a0O,a0V,a0d,a0l,a0t", FractionalIndex.nKeysBetween("a0", "a1", 7).joinToString(","))
        assertEquals("a1,a2,a3", FractionalIndex.nKeysBetween("a0", null, 3).joinToString(","))
        assertEquals(
            "Zz8,ZzG,ZzV,Zzd,Zzl,a0,a0G,a0V,a1,a2",
            FractionalIndex.nKeysBetween("Zz", "a3", 10).joinToString(","),
        )
    }

    /** Many prepends is the composer's everyday path: every new note goes on top. */
    @Test
    fun repeatedPrependsAndAppendsMatchJs() {
        var top: String? = null
        val tops = mutableListOf<String>()
        repeat(70) { i ->
            top = FractionalIndex.keyBetween(null, top)
            if (i % 10 == 9) tops += top!!
        }
        assertEquals("Zr,Zh,ZX,ZN,ZD,Z3,Yzt", tops.joinToString(","))

        var bottom: String? = null
        val bottoms = mutableListOf<String>()
        repeat(70) { i ->
            bottom = FractionalIndex.keyBetween(bottom, null)
            if (i % 10 == 9) bottoms += bottom!!
        }
        assertEquals("a9,aJ,aT,ad,an,ax,b07", bottoms.joinToString(","))
    }

    @Test
    fun rejectsKeysTheJsPackageRejects() {
        assertFalse(FractionalIndex.isValid(""))
        assertFalse(FractionalIndex.isValid("a10")) // trailing zero
        assertFalse(FractionalIndex.isValid("b0")) // integer part too short
        assertFalse(FractionalIndex.isValid("a-"))
        assertTrue(FractionalIndex.isValid("a0"))
        assertTrue(FractionalIndex.isValid("Zz"))
    }

    @Test
    fun safeVariantSurvivesEqualNeighbours() {
        val key = FractionalIndex.keyBetweenSafe("a1", "a1")
        assertTrue(key > "a1")
    }

    @Test
    fun legacyUuidsMatchTheImportScript() {
        assertEquals("ac1274c6-155c-5dbb-bcc6-af2919ed8731", LegacyUuid.section(1))
        assertEquals("a0491ecf-072c-57a7-8bcc-90b3e0ff427e", LegacyUuid.section(12))
        assertEquals("7bfb3a29-8a7a-5604-aef8-9b7c6f6fda64", LegacyUuid.item(1))
        assertEquals("3c56510d-73dd-5ffe-ba49-42c6e229f53c", LegacyUuid.item(42))
    }

    // ── rekeyForOrder: a drag writes one row ──

    private val keys = listOf("a0", "a1", "a2", "a3", "a4")

    private fun applied(newOrder: List<String>): Pair<List<String>, Int> {
        val rekeyed = rekeyForOrder(newOrder)
        val result = newOrder.zip(rekeyed) { old, new -> new ?: old }
        return result to rekeyed.count { it != null }
    }

    @Test
    fun unchangedOrderWritesNothing() {
        assertTrue(rekeyForOrder(keys).all { it == null })
    }

    @Test
    fun dragDownWritesOneRow() {
        val (result, writes) = applied(listOf("a1", "a2", "a3", "a0", "a4"))
        assertEquals(1, writes)
        assertEquals(result.sorted(), result)
    }

    @Test
    fun dragToTopWritesOneRow() {
        val (result, writes) = applied(listOf("a4", "a0", "a1", "a2", "a3"))
        assertEquals(1, writes)
        assertEquals(result.sorted(), result)
    }

    @Test
    fun dragToBottomWritesOneRow() {
        val (result, writes) = applied(listOf("a1", "a2", "a3", "a4", "a0"))
        assertEquals(1, writes)
        assertEquals(result.sorted(), result)
    }

    @Test
    fun invalidAndDuplicateKeysAreRepaired() {
        val (result, writes) = applied(listOf("a0", "", "a1", "a1", "a2"))
        assertEquals(2, writes)
        assertEquals(result.sorted(), result)
        assertEquals(result.size, result.toSet().size)
    }

    @Test
    fun emptyInput() {
        assertTrue(rekeyForOrder(emptyList()).isEmpty())
        assertNull(rekeyForOrder(listOf("a0")).single())
    }
}
