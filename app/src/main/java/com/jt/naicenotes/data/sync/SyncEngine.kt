package com.jt.naicenotes.data.sync

/** What the engine needs from the server. [SupabaseClient] is the real one; tests use a fake. */
interface SyncRemote {
    suspend fun listSpaces(): List<SpaceRow>

    /** Lowest cursor still safe to resume from; below it, purged tombstones may have been missed. */
    suspend fun horizon(): Long

    /** Rows with rev > [since], oldest first. */
    suspend fun sectionsSince(since: Long): List<SectionRow>
    suspend fun itemsSince(since: Long): List<ItemRow>

    /** Everything in [spaceIds], for spaces this device hasn't seen before. */
    suspend fun sectionsInSpaces(spaceIds: List<String>): List<SectionRow>
    suspend fun itemsInSpaces(spaceIds: List<String>): List<ItemRow>

    suspend fun upsertSections(rows: List<SectionRow>)
    suspend fun upsertItems(rows: List<ItemRow>)

    /** Sets deleted_at on existing rows; ids the server doesn't have are a no-op. */
    suspend fun tombstone(table: Table, ids: List<String>)

    enum class Table(val path: String) { SECTIONS("sections"), ITEMS("items") }
}

/** What the engine needs from the device. [RoomSyncStore] is the real one. */
interface SyncStore {
    /** Files every unassigned, non-Claude section under [spaceId]. */
    suspend fun assignUnspacedSections(spaceId: String)

    suspend fun dirtySections(): List<Outgoing<SectionRow>>
    suspend fun dirtyItems(): List<Outgoing<ItemRow>>

    /** Clears `dirty` only if the row is still at [version] — an edit made mid-push stays queued. */
    suspend fun markSectionPushed(uuid: String, version: Int)
    suspend fun markItemPushed(uuid: String, version: Int)

    /** pending_deletes, as (uuid, kind). */
    suspend fun pendingDeletes(): List<Pair<String, String>>
    suspend fun clearPendingDeletes(uuids: List<String>)
    suspend fun queueDeletes(sectionUuids: List<String>, itemUuids: List<String>)

    suspend fun knownSpaceIds(): Set<String>
    suspend fun setSpaces(spaces: List<SpaceRow>)

    /** Drops the clean sections (and their items) of spaces this user can no longer see. */
    suspend fun removeSpaces(spaceIds: Set<String>)

    /** Applies server rows; dirty local rows win. Returns how many local rows changed. */
    suspend fun applySections(rows: List<SectionRow>): Int
    suspend fun applyItems(rows: List<ItemRow>): Int

    /** After a full re-read: deletes clean, once-synced rows the server no longer returned. */
    suspend fun pruneSyncedExcept(sectionUuids: Set<String>, itemUuids: Set<String>): Int

    suspend fun existingSectionUuids(uuids: Collection<String>): Set<String>
    suspend fun existingItemUuids(uuids: Collection<String>): Set<String>

    suspend fun cursor(): Long
    suspend fun setCursor(rev: Long)

    /** Pre-sync ids (see [LegacyUuid]), or null once the first sync has reconciled them. */
    suspend fun unreconciledLegacyIds(): Pair<Set<String>, Set<String>>?
    suspend fun markLegacyReconciled()
}

data class SyncOutcome(
    /** Local rows changed by the pull — the widget re-renders when this isn't 0. */
    val applied: Int,
    /** Rows the server refused for good. They stay on the device but stop being retried. */
    val refused: Int,
    /** Pulled items with a link but no preview; the app fetches them (the PWA can't, CORS). */
    val linksToFetch: Boolean,
)

/**
 * One sync run: push the outbox, then pull everything newer than the cursor. Same contract as
 * the PWA's `sync.ts` (see supabase/README.md): last write wins per row, the server's `rev`
 * orders everything, deletes are tombstones. Not reentrant — [com.jt.naicenotes.sync
 * .SyncController] serialises runs.
 *
 * Two places this deliberately differs from the PWA, both because Room — not the server — has
 * been the only copy of these notes until now:
 * - **A refused row is kept, not discarded.** It stops being retried and the next pull re-reads
 *   everything, so a server copy, if there is one, comes back over it. The PWA drops it.
 * - **The first sync reconciles pre-sync rows.** The 2026-11-11 import put the phone's notes on
 *   the server; anything deleted on the phone since then would otherwise be pulled straight
 *   back. A pulled row with a pre-sync id ([LegacyUuid]) that the phone no longer has was
 *   deleted here, so it's tombstoned instead of re-created. Once only — later, "not on the
 *   phone" can also mean "left that space".
 */
class SyncEngine(
    private val store: SyncStore,
    private val remote: SyncRemote,
    private val log: (String) -> Unit = {},
) {
    private var refused = 0
    private var fullPull = false

    suspend fun sync(): SyncOutcome {
        refused = 0
        fullPull = false

        val spaces = remote.listSpaces()
        // Every account has a personal space from the moment it exists. Without one the
        // listing can't be trusted, and acting on it would drop every section as "gone".
        val personal = spaces.firstOrNull { it.isPersonal }
            ?: throw RemoteException("no personal space in the listing", permanent = false)
        store.assignUnspacedSections(personal.id)

        push()
        val (applied, links, queued) = pull(spaces)
        // Reconciliation turns "deleted on the phone" into tombstones; send them now rather
        // than leave the server showing those notes until the next run.
        if (queued) pushDeletes()
        return SyncOutcome(applied, refused, links)
    }

    // ── Push ──

    private suspend fun push() {
        // Sections first: an item's section must exist on the server before the item can.
        pushRows(store.dirtySections(), remote::upsertSections, store::markSectionPushed) { it.id }
        pushRows(store.dirtyItems(), remote::upsertItems, store::markItemPushed) { it.id }
        pushDeletes()
    }

    private suspend fun <R> pushRows(
        rows: List<Outgoing<R>>,
        send: suspend (List<R>) -> Unit,
        markPushed: suspend (String, Int) -> Unit,
        idOf: (R) -> String,
    ) {
        for (batch in rows.chunked(BATCH)) {
            try {
                send(batch.map { it.row })
                batch.forEach { markPushed(idOf(it.row), it.version) }
                continue
            } catch (e: RemoteException) {
                if (!e.permanent) throw e
            }
            // Refused as a batch: find the culprit row by row so one bad row can't hold up the rest.
            for (out in batch) {
                try {
                    send(listOf(out.row))
                } catch (e: RemoteException) {
                    if (!e.permanent) throw e
                    log("server refused ${idOf(out.row)}: ${e.message}")
                    refused++
                    fullPull = true
                }
                // Pushed or refused, it stops being retried. A refused row stays on the device;
                // the full re-read brings the server's copy back over it if one exists.
                markPushed(idOf(out.row), out.version)
            }
        }
    }

    private suspend fun pushDeletes() {
        val pending = store.pendingDeletes()
        if (pending.isEmpty()) return
        // Items before sections: once a section is tombstoned its items go with it anyway.
        for ((kind, table) in listOf(KIND_ITEM to SyncRemote.Table.ITEMS, KIND_SECTION to SyncRemote.Table.SECTIONS)) {
            for (batch in pending.filter { it.second == kind }.map { it.first }.chunked(BATCH)) {
                try {
                    remote.tombstone(table, batch)
                } catch (e: RemoteException) {
                    if (!e.permanent) throw e
                    for (id in batch) {
                        try {
                            remote.tombstone(table, listOf(id))
                        } catch (e2: RemoteException) {
                            if (!e2.permanent) throw e2
                            // e.g. a member deleting a shared section, which only the owner may.
                            // The full re-read brings it back.
                            log("server refused delete of $id: ${e2.message}")
                            refused++
                            fullPull = true
                        }
                    }
                }
                store.clearPendingDeletes(batch)
            }
        }
    }

    // ── Pull ──

    private data class Pulled(val applied: Int, val linksToFetch: Boolean, val queuedDeletes: Boolean)

    private suspend fun pull(spaces: List<SpaceRow>): Pulled {
        val known = store.knownSpaceIds()
        val current = spaces.mapTo(mutableSetOf()) { it.id }

        // Lost access: nothing will ever tell us those rows changed again, so forget them now.
        store.removeSpaces(known - current)

        var cursor = if (fullPull) 0L else store.cursor()
        var prune = false
        if (cursor > 0 && cursor < remote.horizon()) {
            // Tombstones we never saw have been purged; only a full re-read is trustworthy.
            cursor = 0
            prune = true
        }

        val since = maxOf(0L, cursor - REV_OVERLAP)
        var sections = remote.sectionsSince(since)
        var items = remote.itemsSince(since)

        // A space joined after our cursor moved on: its existing rows have old revs that a
        // rev-based pull never returns, so read those spaces in full.
        val joined = if (cursor == 0L) emptyList() else (current - known).toList()
        if (joined.isNotEmpty()) {
            sections = sections + remote.sectionsInSpaces(joined)
            items = items + remote.itemsInSpaces(joined)
        }

        var queued = false
        store.unreconciledLegacyIds()?.let { (legacySections, legacyItems) ->
            val goneSections = missingLocally(sections, legacySections, { it.id }, { it.deletedAt }, store::existingSectionUuids)
            val goneItems = missingLocally(items, legacyItems, { it.id }, { it.deletedAt }, store::existingItemUuids)
            if (goneSections.isNotEmpty() || goneItems.isNotEmpty()) {
                log("first sync: ${goneSections.size} sections, ${goneItems.size} items deleted here since the import")
                store.queueDeletes(goneSections.toList(), goneItems.toList())
                sections = sections.filterNot { it.id in goneSections }
                items = items.filterNot { it.id in goneItems || it.sectionId in goneSections }
                queued = true
            }
            store.markLegacyReconciled()
        }

        val applied = store.applySections(sections) + store.applyItems(items)
        val pruned = if (prune) {
            store.pruneSyncedExcept(sections.mapTo(HashSet()) { it.id }, items.mapTo(HashSet()) { it.id })
        } else {
            0
        }
        store.setSpaces(spaces)

        val maxRev = maxOf(
            sections.maxOfOrNull { it.rev ?: 0L } ?: 0L,
            items.maxOfOrNull { it.rev ?: 0L } ?: 0L,
            cursor,
        )
        store.setCursor(maxRev)

        val links = items.any { it.deletedAt == null && it.linkUrl != null && it.linkTitle == null && !it.linkFetchFailed }
        return Pulled(applied + pruned, links, queued)
    }

    private suspend fun <R> missingLocally(
        rows: List<R>,
        legacy: Set<String>,
        id: (R) -> String,
        deletedAt: (R) -> String?,
        existing: suspend (Collection<String>) -> Set<String>,
    ): Set<String> {
        val candidates = rows.filter { deletedAt(it) == null && id(it) in legacy }.map(id)
        if (candidates.isEmpty()) return emptySet()
        return candidates.toSet() - existing(candidates)
    }

    companion object {
        /**
         * How far behind the cursor each pull re-reads. Revs come from a sequence that hands
         * out numbers before commit, so a slow transaction can become visible after a faster
         * one with a higher rev. Applying a row twice is harmless. Same value as the PWA.
         */
        const val REV_OVERLAP = 200L

        /** Rows per request — keeps a first sync's upload well inside request size limits. */
        const val BATCH = 200

        const val KIND_SECTION = "section"
        const val KIND_ITEM = "item"
    }
}
