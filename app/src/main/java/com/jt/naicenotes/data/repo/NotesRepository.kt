package com.jt.naicenotes.data.repo

import androidx.room.withTransaction
import com.jt.naicenotes.data.db.AppDatabase
import com.jt.naicenotes.data.db.SectionItemBucket
import com.jt.naicenotes.data.db.keyAfter
import com.jt.naicenotes.data.entity.Item
import com.jt.naicenotes.data.entity.PendingDelete
import com.jt.naicenotes.data.entity.Section
import com.jt.naicenotes.data.remote.LinkDetector
import com.jt.naicenotes.data.util.nextDueAt
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Every local write goes through here, which is what makes sync tractable: each mutator marks
 * its rows dirty (the DAOs' `TOUCH`), records deletes in the outbox, and [onChange] asks for a
 * sync. Pulled rows bypass this class entirely ([com.jt.naicenotes.data.sync.RoomSyncStore]),
 * so applying a pull never queues a push of its own.
 */
class NotesRepository(
    private val db: AppDatabase,
    private val onChange: suspend () -> Unit = {},
    /**
     * Called when an added item turns out to contain a URL. The app wires this to a
     * background Open Graph fetch so every add path gets previews without knowing about
     * networking.
     */
    private val onLinkDetected: (itemId: Long, url: String) -> Unit = { _, _ -> },
    /**
     * Called when an item is added to a section that pushes to a remote inbox. Same idea as
     * [onLinkDetected]: every add path — composer, share target, widget quick-add — gets the
     * behaviour without knowing that networking is involved.
     */
    private val onInboxItem: (item: Item, sectionName: String) -> Unit = { _, _ -> },
) {

    private val sections = db.sectionDao()
    private val items = db.itemDao()
    private val outbox = db.syncDao()

    fun observeSections(): Flow<List<Section>> = sections.observeAll()

    fun observeItems(sectionId: Long): Flow<List<Item>> = items.observeBySection(sectionId)

    /**
     * Raw item buckets for the rail badges, header counts and clear totals. Deliberately not folded
     * into counts here: folding needs the current time, and a clock held anywhere behind the
     * Flow is a clock that stops. The UI applies it — see
     * [com.jt.naicenotes.data.util.countsBySection].
     */
    fun observeItemBuckets(): Flow<List<SectionItemBucket>> = items.observeItemBuckets()

    suspend fun listItems(sectionId: Long): List<Item> = items.listBySection(sectionId)

    suspend fun sectionCount(): Int = sections.count()

    suspend fun addSection(name: String, color: Int, emoji: String? = null): Long {
        val id = sections.insert(
            Section(name = name, color = color, emoji = emoji, sortKey = keyAfter(sections.lastKey())),
        )
        onChange()
        return id
    }

    /**
     * Everything a section's dialog can change, in one write. Name, icon, colour and the
     * Claude flag are edited together, and saving them separately would mean three writes and
     * three widget re-renders for one press of Save — and, worse, each one overwriting the
     * stale copy the others started from.
     */
    suspend fun updateSection(
        section: Section,
        name: String,
        emoji: String?,
        color: Int,
        makeClaudeSection: Boolean,
    ) {
        db.withTransaction {
            if (makeClaudeSection && !section.isClaudeSection) {
                sections.clearRemoteKind(Section.REMOTE_KIND_CLAUDE, exceptId = section.id)
                // The Claude section is device-only, so this one leaves the server: tombstone
                // its server copy, then give it and its notes fresh identities so that
                // tombstone coming back in a pull can't delete the local rows.
                outbox.queueDeletes(listOf(PendingDelete(section.uuid, PendingDelete.KIND_SECTION)))
                sections.detach(section.id, UUID.randomUUID().toString())
                items.idsInSection(section.id).forEach { items.setUuid(it, UUID.randomUUID().toString()) }
            }
            sections.setDetails(
                id = section.id,
                name = name,
                emoji = emoji,
                color = color,
                remoteKind = if (makeClaudeSection) Section.REMOTE_KIND_CLAUDE else section.remoteKind,
            )
        }
        onChange()
    }

    /** The server tombstones the section's items itself; locally they go by foreign-key cascade. */
    suspend fun deleteSection(section: Section) {
        db.withTransaction {
            outbox.queueDeletes(listOf(PendingDelete(section.uuid, PendingDelete.KIND_SECTION)))
            sections.deleteById(section.id)
        }
        onChange()
    }

    suspend fun reorderSections(newOrder: List<Long>) {
        sections.reorder(newOrder)
        onChange()
    }

    /** New items land at the top of the section, not the bottom. */
    suspend fun addItem(
        sectionId: Long,
        text: String,
        dueAt: Long? = null,
        repeatWeeks: Int? = null,
    ): Long {
        val url = LinkDetector.findUrl(text)
        val row = Item(
            sectionId = sectionId,
            text = text,
            linkUrl = url,
            dueAt = dueAt,
            repeatWeeks = repeatWeeks,
        )
        val id = items.insertAtTop(row)
        onChange()
        if (url != null) onLinkDetected(id, url)
        notifyIfClaudeSection(sectionId) { section -> onInboxItem(row.copy(id = id), section.name) }
        return id
    }

    suspend fun setLinkPreview(id: Long, title: String?, imageUrl: String?) {
        items.setLinkPreview(id, title, imageUrl)
        onChange()
    }

    /** Links whose preview never landed — offline at share time, or a transient failure. */
    suspend fun linksMissingPreview(): List<Item> = items.listLinksMissingPreview()

    /** Stop retrying a link whose fetch failed permanently (blocked, gone, no metadata). */
    suspend fun markLinkFetchFailed(id: Long) = items.markLinkFetchFailed(id)

    suspend fun bulkAddItems(sectionId: Long, texts: List<String>): List<Long> {
        if (texts.isEmpty()) return emptyList()
        val rows = texts.map { Item(sectionId = sectionId, text = it) }
        val ids = items.insertAllAtTop(sectionId, rows)
        onChange()
        notifyIfClaudeSection(sectionId) { section ->
            rows.zip(ids).forEach { (row, id) -> onInboxItem(row.copy(id = id), section.name) }
        }
        return ids
    }

    /**
     * Runs [block] only when the target section pushes to a remote inbox. One section lookup
     * per add, and none of the callers need to know whether the section is special.
     */
    private suspend fun notifyIfClaudeSection(sectionId: Long, block: (Section) -> Unit) {
        val section = sections.byId(sectionId) ?: return
        if (section.isClaudeSection) block(section)
    }

    suspend fun markPushed(itemId: Long, at: Long = System.currentTimeMillis()) {
        items.markPushed(itemId, at)
        onChange()
    }

    /**
     * Notes still owed to the inbox, each with its section name. Drives the launch-time
     * retry, so it covers anything captured while offline.
     */
    suspend fun pendingInboxItems(): List<PendingInboxItem> {
        val pending = items.listUnpushedInRemoteSections()
        if (pending.isEmpty()) return emptyList()
        val names = pending.map { it.sectionId }.distinct()
            .mapNotNull { id -> sections.byId(id)?.let { id to it.name } }
            .toMap()
        return pending.mapNotNull { item ->
            names[item.sectionId]?.let { PendingInboxItem(item, it) }
        }
    }

    suspend fun setSchedule(item: Item, dueAt: Long?, repeatWeeks: Int?) {
        items.setSchedule(item.id, dueAt, repeatWeeks)
        onChange()
    }

    /**
     * Start a repeating note's cycle again. Ticking one off deliberately doesn't do this —
     * "done" and "owed again" are different claims, and only the second is worth guessing at.
     */
    suspend fun resetTimer(item: Item, now: Long = System.currentTimeMillis()) {
        val weeks = item.repeatWeeks ?: return
        items.setSchedule(item.id, nextDueAt(now, weeks), weeks)
        onChange()
    }

    suspend fun toggleItem(item: Item) {
        items.setChecked(item.id, !item.isChecked)
        onChange()
    }

    suspend fun toggleItemById(id: Long) {
        items.toggleById(id)
        onChange()
    }

    suspend fun updateItemText(item: Item, newText: String) {
        items.setText(item.id, newText)
        onChange()
    }

    suspend fun deleteItem(item: Item) {
        db.withTransaction {
            outbox.queueDeletes(listOf(PendingDelete(item.uuid, PendingDelete.KIND_ITEM)))
            items.deleteById(item.id)
        }
        onChange()
    }

    /**
     * Move an item to another section. Lands at the top, matching where a newly added item
     * goes. Pushing to the inbox is deliberately not re-triggered: `pushedAt` already records
     * whether this note reached the vault, and moving it between local sections doesn't change
     * that — re-pushing on every move would duplicate tasks.
     */
    suspend fun moveItemToSection(item: Item, targetSectionId: Long) {
        if (item.sectionId == targetSectionId) return
        db.withTransaction {
            val source = sections.byId(item.sectionId)
            val target = sections.byId(targetSectionId) ?: return@withTransaction
            // The server refuses moving a row between spaces. Across that line a move is a
            // delete of the old row plus a new one, so the old space stops showing it.
            val personal = outbox.personalSpaceId()
            val uuid = if (source == null || spaceOf(source, personal) != spaceOf(target, personal)) {
                outbox.queueDeletes(listOf(PendingDelete(item.uuid, PendingDelete.KIND_ITEM)))
                UUID.randomUUID().toString()
            } else {
                item.uuid
            }
            items.moveToSectionTop(item.id, targetSectionId, uuid)
        }
        onChange()
    }

    /** Where a section syncs to: unassigned means "personal, at the next sync"; Claude means nowhere. */
    private fun spaceOf(section: Section, personal: String?): String =
        if (section.isClaudeSection) "device" else section.spaceId ?: personal ?: "personal"

    /**
     * Re-insert an item that was just deleted, preserving text, checked state and sort key (so
     * its original place). Same uuid, and its queued tombstone is withdrawn: the server copy is
     * revived instead of deleted and duplicated.
     */
    suspend fun restoreItem(item: Item): Long {
        val id = db.withTransaction {
            outbox.unqueueDelete(item.uuid)
            items.insert(item.copy(id = 0, dirty = true, version = item.version + 1))
        }
        onChange()
        return id
    }

    suspend fun clearCheckedItems(sectionId: Long) {
        db.withTransaction {
            outbox.queueCheckedItemDeletes(sectionId)
            items.deleteCheckedInSection(sectionId)
        }
        onChange()
    }

    /** Empties a section. Irreversible, like [deleteSection] — both sit behind a confirm dialog. */
    suspend fun clearSection(sectionId: Long) {
        db.withTransaction {
            outbox.queueAllItemDeletes(sectionId)
            items.deleteAllInSection(sectionId)
        }
        onChange()
    }

    suspend fun reorderItems(newOrder: List<Long>) {
        items.reorder(newOrder)
        onChange()
    }

}

/** An item owed to a remote inbox, paired with the section name the inbox wants reported. */
data class PendingInboxItem(val item: Item, val sectionName: String)
