package com.jt.naicenotes.data.repo

import com.jt.naicenotes.data.db.AppDatabase
import com.jt.naicenotes.data.entity.Item
import com.jt.naicenotes.data.entity.Section
import com.jt.naicenotes.data.remote.LinkDetector
import kotlinx.coroutines.flow.Flow

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

    fun observeSections(): Flow<List<Section>> = sections.observeAll()

    fun observeItems(sectionId: Long): Flow<List<Item>> = items.observeBySection(sectionId)

    suspend fun listItems(sectionId: Long): List<Item> = items.listBySection(sectionId)

    suspend fun sectionCount(): Int = sections.count()

    suspend fun addSection(name: String, color: Int): Long {
        val nextPosition = sections.maxPosition() + 1
        val id = sections.insert(Section(name = name, color = color, position = nextPosition))
        onChange()
        return id
    }

    suspend fun renameSection(section: Section, newName: String) {
        sections.update(section.copy(name = newName))
        onChange()
    }

    suspend fun recolorSection(section: Section, newColor: Int) {
        sections.update(section.copy(color = newColor))
        onChange()
    }

    suspend fun deleteSection(section: Section) {
        sections.delete(section)
        onChange()
    }

    suspend fun reorderSections(newOrder: List<Long>) {
        newOrder.forEachIndexed { index, id -> sections.setPosition(id, index) }
        onChange()
    }

    /** New items land at the top of the section, not the bottom. */
    suspend fun addItem(sectionId: Long, text: String): Long {
        val url = LinkDetector.findUrl(text)
        val row = Item(sectionId = sectionId, text = text, position = 0, linkUrl = url)
        val id = items.insertAtTop(row)
        onChange()
        if (url != null) onLinkDetected(id, url)
        notifyIfInbox(sectionId) { section -> onInboxItem(row.copy(id = id), section.name) }
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
        val rows = texts.map { Item(sectionId = sectionId, text = it, position = 0) }
        val ids = items.insertAllAtTop(sectionId, rows)
        onChange()
        notifyIfInbox(sectionId) { section ->
            rows.zip(ids).forEach { (row, id) -> onInboxItem(row.copy(id = id), section.name) }
        }
        return ids
    }

    /**
     * Runs [block] only when the target section pushes to a remote inbox. One section lookup
     * per add, and none of the callers need to know whether the section is special.
     */
    private suspend fun notifyIfInbox(sectionId: Long, block: (Section) -> Unit) {
        val section = sections.byId(sectionId) ?: return
        if (section.isInbox) block(section)
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

    suspend fun setSectionRemoteKind(section: Section, remoteKind: String?) {
        sections.update(section.copy(remoteKind = remoteKind))
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
        items.update(item.copy(text = newText))
        onChange()
    }

    suspend fun deleteItem(item: Item) {
        items.delete(item)
        onChange()
    }

    /** Re-insert an item that was just deleted, preserving text/checked/position. */
    suspend fun restoreItem(item: Item): Long {
        val id = items.insert(item.copy(id = 0))
        onChange()
        return id
    }

    suspend fun clearCheckedItems(sectionId: Long) {
        items.deleteCheckedInSection(sectionId)
        onChange()
    }

    suspend fun reorderItems(newOrder: List<Long>) {
        newOrder.forEachIndexed { index, id -> items.setPosition(id, index) }
        onChange()
    }

    suspend fun moveDoneToBottom(sectionId: Long) {
        val current = items.listBySection(sectionId)
        val reordered = current.filter { !it.isChecked } + current.filter { it.isChecked }
        reordered.forEachIndexed { index, item -> items.setPosition(item.id, index) }
        onChange()
    }
}

/** An item owed to a remote inbox, paired with the section name the inbox wants reported. */
data class PendingInboxItem(val item: Item, val sectionName: String)
