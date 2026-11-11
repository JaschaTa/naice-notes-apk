package com.jt.naicenotes

import com.jt.naicenotes.data.sync.ItemRow
import com.jt.naicenotes.data.sync.LegacyUuid
import com.jt.naicenotes.data.sync.Outgoing
import com.jt.naicenotes.data.sync.RemoteException
import com.jt.naicenotes.data.sync.SectionRow
import com.jt.naicenotes.data.sync.SpaceRow
import com.jt.naicenotes.data.sync.SyncEngine
import com.jt.naicenotes.data.sync.SyncRemote
import com.jt.naicenotes.data.sync.SyncStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The engine against an in-memory server and device that model the real semantics: a global
 * rev sequence, tombstones, dirty-wins on apply, and version-checked clearing. The Room store's
 * SQL isn't exercised here (no Android runtime in JVM tests) — this locks the protocol.
 */
class SyncEngineTest {

    private val personal = SpaceRow("space-p", "Personal", "personal", "me")
    private val shared = SpaceRow("space-s", "Groceries", "shared", "me")

    // ── Fakes ──

    private class FakeServer(var spaces: MutableList<SpaceRow>) : SyncRemote {
        var rev = 0L
        val sections = linkedMapOf<String, SectionRow>()
        val items = linkedMapOf<String, ItemRow>()
        var offline = false
        val refuseItems = mutableSetOf<String>()

        fun putSection(r: SectionRow) { sections[r.id] = r.copy(rev = ++rev) }
        fun putItem(r: ItemRow) { items[r.id] = r.copy(rev = ++rev) }

        private fun check() { if (offline) throw RemoteException("network", permanent = false) }

        override suspend fun listSpaces() = check().let { spaces.toList() }
        override suspend fun horizon() = 0L
        // RLS: only rows in spaces the user is a member of are visible at all.
        private fun visible(spaceId: String?) = spaces.any { it.id == spaceId }
        override suspend fun sectionsSince(since: Long) =
            sections.values.filter { it.rev!! > since && visible(it.spaceId) }.sortedBy { it.rev }
        override suspend fun itemsSince(since: Long) =
            items.values.filter { it.rev!! > since && visible(sections[it.sectionId]?.spaceId) }.sortedBy { it.rev }
        override suspend fun sectionsInSpaces(spaceIds: List<String>) = sections.values.filter { it.spaceId in spaceIds }
        override suspend fun itemsInSpaces(spaceIds: List<String>) =
            items.values.filter { sections[it.sectionId]?.spaceId in spaceIds }

        override suspend fun upsertSections(rows: List<SectionRow>) {
            check()
            rows.forEach { r -> putSection(r.copy(spaceId = sections[r.id]?.spaceId ?: r.spaceId)) }
        }

        override suspend fun upsertItems(rows: List<ItemRow>) {
            check()
            if (rows.any { it.id in refuseItems }) throw RemoteException("refused", permanent = true)
            rows.forEach { r ->
                if (r.sectionId !in sections) throw RemoteException("fk", permanent = true)
                putItem(r)
            }
        }

        override suspend fun tombstone(table: SyncRemote.Table, ids: List<String>) {
            check()
            for (id in ids) when (table) {
                SyncRemote.Table.ITEMS -> items[id]?.let { putItem(it.copy(deletedAt = "now")) }
                SyncRemote.Table.SECTIONS -> sections[id]?.let { putSection(it.copy(deletedAt = "now")) }
            }
        }
    }

    private data class LocalSection(val row: SectionRow?, val spaceId: String?, val dirty: Boolean, val version: Int, val claude: Boolean = false, val draft: SectionRow)
    private data class LocalItem(val row: ItemRow, val dirty: Boolean, val version: Int)

    private class FakeStore(legacySections: Set<String> = emptySet(), legacyItems: Set<String> = emptySet()) : SyncStore {
        val sections = linkedMapOf<String, LocalSection>()
        val items = linkedMapOf<String, LocalItem>()
        val deletes = linkedMapOf<String, String>()
        var spaces = setOf<String>()
        var cursor = 0L
        var legacy: Pair<Set<String>, Set<String>>? = legacySections to legacyItems

        fun addSection(id: String, name: String = id, claude: Boolean = false, dirty: Boolean = true, spaceId: String? = null) {
            val draft = SectionRow(id, spaceId ?: "", name, 0, null, "a0", 1, null)
            sections[id] = LocalSection(null, spaceId, dirty, 1, claude, draft)
        }

        fun addItem(id: String, section: String, text: String = id, dirty: Boolean = true) {
            items[id] = LocalItem(ItemRow(id, section, text, false, "a0", 1, null, null, null, null, false, null, null, null), dirty, 1)
        }

        fun edit(id: String, text: String) {
            val i = items.getValue(id)
            items[id] = i.copy(row = i.row.copy(text = text), dirty = true, version = i.version + 1)
        }

        override suspend fun assignUnspacedSections(spaceId: String) {
            sections.replaceAll { _, s -> if (s.spaceId == null && !s.claude) s.copy(spaceId = spaceId) else s }
        }

        override suspend fun dirtySections() = sections.values
            .filter { it.dirty && !it.claude && it.spaceId != null }
            .map { Outgoing(it.draft.copy(spaceId = it.spaceId!!), it.version) }

        override suspend fun dirtyItems() = items.values
            .filter { i -> i.dirty && sections[i.row.sectionId]?.let { !it.claude && it.spaceId != null } == true }
            .map { Outgoing(it.row, it.version) }

        override suspend fun markSectionPushed(uuid: String, version: Int) {
            sections[uuid]?.takeIf { it.version == version }?.let { sections[uuid] = it.copy(dirty = false) }
        }

        override suspend fun markItemPushed(uuid: String, version: Int) {
            items[uuid]?.takeIf { it.version == version }?.let { items[uuid] = it.copy(dirty = false) }
        }

        override suspend fun pendingDeletes() = deletes.map { it.key to it.value }
        override suspend fun clearPendingDeletes(uuids: List<String>) { uuids.forEach(deletes::remove) }
        override suspend fun queueDeletes(sectionUuids: List<String>, itemUuids: List<String>) {
            sectionUuids.forEach { deletes[it] = "section" }
            itemUuids.forEach { deletes[it] = "item" }
        }

        override suspend fun knownSpaceIds() = spaces
        override suspend fun setSpaces(spaces: List<SpaceRow>) { this.spaces = spaces.map { it.id }.toSet() }
        override suspend fun removeSpaces(spaceIds: Set<String>) {
            val gone = sections.filter { it.value.spaceId in spaceIds && !it.value.dirty }.keys
            gone.forEach { id -> sections.remove(id); items.values.removeAll { it.row.sectionId == id } }
        }

        override suspend fun applySections(rows: List<SectionRow>): Int {
            var n = 0
            for (r in rows) {
                val local = sections[r.id]
                if (local != null && (local.dirty || local.claude)) continue
                if (r.deletedAt != null) {
                    if (local != null) { sections.remove(r.id); items.values.removeAll { it.row.sectionId == r.id }; n++ }
                } else {
                    sections[r.id] = LocalSection(r, r.spaceId, false, local?.version ?: 0, false, r); n++
                }
            }
            return n
        }

        override suspend fun applyItems(rows: List<ItemRow>): Int {
            var n = 0
            for (r in rows) {
                val local = items[r.id]
                if (local?.dirty == true) continue
                if (r.deletedAt != null) { if (items.remove(r.id) != null) n++; continue }
                val section = sections[r.sectionId] ?: continue
                if (section.claude) continue
                items[r.id] = LocalItem(r, false, local?.version ?: 0); n++
            }
            return n
        }

        override suspend fun pruneSyncedExcept(sectionUuids: Set<String>, itemUuids: Set<String>) = 0
        override suspend fun existingSectionUuids(uuids: Collection<String>) = uuids.filter { it in sections }.toSet()
        override suspend fun existingItemUuids(uuids: Collection<String>) = uuids.filter { it in items }.toSet()
        override suspend fun cursor() = cursor
        override suspend fun setCursor(rev: Long) { cursor = rev }
        override suspend fun unreconciledLegacyIds() = legacy
        override suspend fun markLegacyReconciled() { legacy = null }
    }

    private fun item(id: String, section: String, text: String = id) =
        ItemRow(id, section, text, false, "a0", 1, null, null, null, null, false, null, null, null)

    // ── Tests ──

    @Test
    fun firstSyncUploadsEverythingExceptTheClaudeSection() = runBlocking {
        val server = FakeServer(mutableListOf(personal))
        val store = FakeStore()
        store.addSection("s1")
        store.addSection("claude", claude = true)
        store.addItem("i1", "s1")
        store.addItem("receipt", "claude")

        SyncEngine(store, server).sync()

        assertEquals(setOf("s1"), server.sections.keys)
        assertEquals("space-p", server.sections.getValue("s1").spaceId)
        assertEquals(setOf("i1"), server.items.keys)
        assertFalse(store.items.getValue("i1").dirty)
        assertTrue("Claude rows are never cleared, never sent", store.items.getValue("receipt").dirty)
    }

    @Test
    fun anEditMadeWhilePushingStaysQueued() = runBlocking {
        val real = FakeServer(mutableListOf(personal))
        val store = FakeStore()
        store.addSection("s1")
        store.addItem("i1", "s1", "milk")
        // Edit lands between reading the dirty rows and marking them pushed.
        val racing = object : SyncRemote by real {
            override suspend fun upsertItems(rows: List<ItemRow>) {
                real.upsertItems(rows)
                store.edit("i1", "oat milk")
            }
        }
        SyncEngine(store, racing).sync()
        assertTrue(store.items.getValue("i1").dirty)
        assertEquals("milk", real.items.getValue("i1").text)

        SyncEngine(store, racing).sync()
        assertEquals("oat milk", real.items.getValue("i1").text)
    }

    @Test
    fun aDirtyLocalRowWinsOverThePull() = runBlocking {
        val server = FakeServer(mutableListOf(personal))
        val store = FakeStore()
        store.addSection("s1")
        store.addItem("i1", "s1", "milk")
        SyncEngine(store, server).sync()

        server.putItem(item("i1", "s1", "from the web"))
        store.edit("i1", "from the phone")
        SyncEngine(store, server).sync()

        assertEquals("from the phone", server.items.getValue("i1").text)
        assertEquals("from the phone", store.items.getValue("i1").row.text)
    }

    @Test
    fun remoteEditsAndDeletesArrive() = runBlocking {
        val server = FakeServer(mutableListOf(personal))
        val store = FakeStore()
        store.addSection("s1")
        store.addItem("i1", "s1")
        SyncEngine(store, server).sync()

        server.putItem(item("web", "s1", "added on the iPhone"))
        server.putItem(server.items.getValue("i1").copy(deletedAt = "now"))
        SyncEngine(store, server).sync()

        assertEquals(setOf("web"), store.items.keys)
    }

    @Test
    fun localDeletesBecomeTombstones() = runBlocking {
        val server = FakeServer(mutableListOf(personal))
        val store = FakeStore()
        store.addSection("s1")
        store.addItem("i1", "s1")
        SyncEngine(store, server).sync()

        store.items.remove("i1")
        store.queueDeletes(emptyList(), listOf("i1"))
        SyncEngine(store, server).sync()

        assertEquals("now", server.items.getValue("i1").deletedAt)
        assertTrue(store.deletes.isEmpty())
        assertTrue(store.items.isEmpty())
    }

    @Test
    fun offlineKeepsTheOutboxAndTouchesNothing() = runBlocking {
        val server = FakeServer(mutableListOf(personal)).apply { offline = true }
        val store = FakeStore()
        store.addSection("s1")
        store.addItem("i1", "s1")
        try {
            SyncEngine(store, server).sync()
            fail("expected offline")
        } catch (e: RemoteException) {
            assertFalse(e.permanent)
        }
        assertTrue(store.items.getValue("i1").dirty)
        assertEquals(0L, store.cursor)
    }

    /** The 2026-11-11 import put the phone's notes on the server; some were deleted since. */
    @Test
    fun firstSyncTombstonesImportedNotesDeletedOnThePhone() = runBlocking {
        val sec = LegacyUuid.section(1)
        val kept = LegacyUuid.item(1)
        val deleted = LegacyUuid.item(2)
        val server = FakeServer(mutableListOf(personal))
        server.putSection(SectionRow(sec, "space-p", "Shopping", 0, null, "a0", 1, null))
        server.putItem(item(kept, sec, "kept"))
        server.putItem(item(deleted, sec, "deleted on the phone"))
        server.putItem(item("web-1", sec, "added on the web"))

        val store = FakeStore(legacySections = setOf(sec), legacyItems = setOf(kept, deleted))
        store.addSection(sec, "Shopping")
        store.addItem(kept, sec, "kept, edited")

        SyncEngine(store, server).sync()

        assertEquals("now", server.items.getValue(deleted).deletedAt)
        assertNull(server.items.getValue(kept).deletedAt)
        assertEquals("kept, edited", server.items.getValue(kept).text)
        assertEquals(setOf(kept, "web-1"), store.items.keys)
        assertNull(store.legacy)
    }

    @Test
    fun refusedRowIsKeptButStopsRetrying() = runBlocking {
        val server = FakeServer(mutableListOf(personal))
        val store = FakeStore()
        store.addSection("s1")
        store.addItem("good", "s1")
        store.addItem("bad", "s1")
        server.refuseItems += "bad"

        val outcome = SyncEngine(store, server).sync()

        assertEquals(1, outcome.refused)
        assertTrue("good" in server.items)
        assertTrue("bad" in store.items)
        assertFalse(store.items.getValue("bad").dirty)
    }

    @Test
    fun aJoinedSpaceIsReadInFull() = runBlocking {
        val server = FakeServer(mutableListOf(personal))
        val store = FakeStore()
        store.addSection("s1")
        SyncEngine(store, server).sync()
        // Shared with us after our cursor moved past these revs.
        server.putSection(SectionRow("g", "space-s", "Groceries", 0, null, "a1", 1, null))
        server.putItem(item("g1", "g"))
        repeat(300) { server.putItem(item("noise$it", "s1")) }
        store.cursor = server.rev
        server.spaces += shared

        SyncEngine(store, server).sync()

        assertTrue("g" in store.sections)
        assertTrue("g1" in store.items)
    }

    /**
     * Leaving (or being removed from) a space: nothing will ever announce its rows again, so
     * they go. A pending edit in it was already pushed earlier in the same run — push precedes
     * pull — so nothing unsent is lost by the time the removal happens.
     */
    @Test
    fun aVanishedSpaceIsDropped() = runBlocking {
        val server = FakeServer(mutableListOf(personal, shared))
        server.putSection(SectionRow("g", "space-s", "Groceries", 0, null, "a1", 1, null))
        server.putItem(item("g1", "g"))
        val store = FakeStore()
        store.addSection("mine")
        SyncEngine(store, server).sync()
        assertEquals(setOf("mine", "g"), store.sections.keys)

        server.spaces.remove(shared)
        SyncEngine(store, server).sync()

        assertEquals(setOf("mine"), store.sections.keys)
        assertFalse("g1" in store.items)
    }

    @Test
    fun noPersonalSpaceMeansTheListingIsNotTrusted() = runBlocking {
        val server = FakeServer(mutableListOf())
        val store = FakeStore()
        store.addSection("s1", dirty = false, spaceId = "space-p")
        store.spaces = setOf("space-p")
        try {
            SyncEngine(store, server).sync()
            fail("expected an error")
        } catch (e: RemoteException) {
            assertFalse(e.permanent)
        }
        assertTrue("s1" in store.sections)
    }
}
