package com.jt.naicenotes.data.sync

import androidx.room.withTransaction
import com.jt.naicenotes.data.db.AppDatabase
import com.jt.naicenotes.data.entity.Item
import com.jt.naicenotes.data.entity.PendingDelete
import com.jt.naicenotes.data.entity.Section
import com.jt.naicenotes.data.entity.SyncMeta
import com.jt.naicenotes.data.entity.SyncSpace
import java.time.Instant

/** [SyncStore] over Room. Local integer ids never leave this class; the server only sees uuids. */
class RoomSyncStore(private val db: AppDatabase) : SyncStore {

    private val dao = db.syncDao()

    override suspend fun assignUnspacedSections(spaceId: String) = dao.assignUnspacedSections(spaceId)

    override suspend fun dirtySections(): List<Outgoing<SectionRow>> =
        dao.dirtySections().map { Outgoing(it.toRow(), it.version) }

    override suspend fun dirtyItems(): List<Outgoing<ItemRow>> =
        dao.dirtyItems().map { Outgoing(it.item.toRow(it.sectionUuid), it.item.version) }

    override suspend fun markSectionPushed(uuid: String, version: Int) = dao.markSectionPushed(uuid, version)

    override suspend fun markItemPushed(uuid: String, version: Int) = dao.markItemPushed(uuid, version)

    override suspend fun pendingDeletes(): List<Pair<String, String>> =
        dao.pendingDeletes().map { it.uuid to it.kind }

    override suspend fun clearPendingDeletes(uuids: List<String>) = dao.clearPendingDeletes(uuids)

    override suspend fun queueDeletes(sectionUuids: List<String>, itemUuids: List<String>) {
        dao.queueDeletes(
            sectionUuids.map { PendingDelete(it, PendingDelete.KIND_SECTION) } +
                itemUuids.map { PendingDelete(it, PendingDelete.KIND_ITEM) },
        )
    }

    override suspend fun knownSpaceIds(): Set<String> = dao.spaces().mapTo(mutableSetOf()) { it.id }

    override suspend fun setSpaces(spaces: List<SpaceRow>) = db.withTransaction {
        dao.clearSpaces()
        dao.insertSpaces(spaces.map { SyncSpace(it.id, it.name, it.kind, it.createdBy) })
    }

    override suspend fun removeSpaces(spaceIds: Set<String>) {
        if (spaceIds.isNotEmpty()) dao.deleteCleanSectionsInSpaces(spaceIds.toList())
    }

    override suspend fun applySections(rows: List<SectionRow>): Int = db.withTransaction {
        var applied = 0
        for (r in rows) {
            val local = dao.sectionByUuid(r.id)
            // A dirty row is newer as far as this device knows; the next push makes it the
            // server's too. The Claude section is never on the server — a row that claims to
            // be it is a leftover from before it was designated, and must not touch it.
            if (local != null && (local.dirty || local.isClaudeSection)) continue
            when {
                r.deletedAt != null -> if (local != null) {
                    dao.deleteSection(local.id)
                    applied++
                }
                local == null -> {
                    dao.insertSection(
                        Section(
                            name = r.name, color = r.color, emoji = r.emoji, createdAt = r.createdAt,
                            uuid = r.id, sortKey = r.sortKey, spaceId = r.spaceId, rev = r.rev,
                            dirty = false, version = 0,
                        ),
                    )
                    applied++
                }
                else -> {
                    val next = local.copy(
                        name = r.name, color = r.color, emoji = r.emoji, sortKey = r.sortKey,
                        spaceId = r.spaceId, rev = r.rev,
                    )
                    if (next != local) {
                        dao.updateSection(next)
                        applied++
                    }
                }
            }
        }
        applied
    }

    override suspend fun applyItems(rows: List<ItemRow>): Int = db.withTransaction {
        var applied = 0
        for (r in rows) {
            val local = dao.itemByUuid(r.id)
            if (local != null && local.dirty) continue
            if (local != null && dao.sectionById(local.sectionId)?.isClaudeSection != false) continue
            if (r.deletedAt != null) {
                if (local != null) {
                    dao.deleteItem(local.id)
                    applied++
                }
                continue
            }
            // Its section was deleted here (tombstone still queued) or is the Claude section:
            // nowhere to put it. A later pull brings it once the section is back, if ever.
            val section = dao.sectionByUuid(r.sectionId)
            if (section == null || section.isClaudeSection) continue
            if (local == null) {
                dao.insertItem(
                    Item(
                        sectionId = section.id, text = r.text, isChecked = r.isChecked,
                        createdAt = r.createdAt, linkUrl = r.linkUrl, linkTitle = r.linkTitle,
                        linkImageUrl = r.linkImageUrl, linkFetchFailed = r.linkFetchFailed,
                        dueAt = r.dueAt, repeatWeeks = r.repeatWeeks, uuid = r.id,
                        sortKey = r.sortKey, createdBy = r.createdBy, rev = r.rev,
                        dirty = false, version = 0,
                    ),
                )
                applied++
            } else {
                val next = local.copy(
                    sectionId = section.id, text = r.text, isChecked = r.isChecked,
                    linkUrl = r.linkUrl, linkTitle = r.linkTitle, linkImageUrl = r.linkImageUrl,
                    linkFetchFailed = r.linkFetchFailed, dueAt = r.dueAt, repeatWeeks = r.repeatWeeks,
                    sortKey = r.sortKey, createdBy = r.createdBy, rev = r.rev,
                )
                if (next != local) {
                    dao.updateItem(next)
                    applied++
                }
            }
        }
        applied
    }

    override suspend fun pruneSyncedExcept(sectionUuids: Set<String>, itemUuids: Set<String>): Int =
        db.withTransaction {
            val items = dao.syncedCleanItemUuids().filterNot { it in itemUuids }
            val sections = dao.syncedCleanSectionUuids().filterNot { it in sectionUuids }
            items.chunked(CHUNK).sumOf { dao.deleteItemsByUuid(it) } +
                sections.chunked(CHUNK).sumOf { dao.deleteSectionsByUuid(it) }
        }

    override suspend fun existingSectionUuids(uuids: Collection<String>): Set<String> =
        uuids.chunked(CHUNK).flatMapTo(mutableSetOf()) { dao.existingSectionUuids(it) }

    override suspend fun existingItemUuids(uuids: Collection<String>): Set<String> =
        uuids.chunked(CHUNK).flatMapTo(mutableSetOf()) { dao.existingItemUuids(it) }

    override suspend fun cursor(): Long = dao.meta(META_CURSOR)?.toLongOrNull() ?: 0L

    override suspend fun setCursor(rev: Long) = dao.setMeta(SyncMeta(META_CURSOR, rev.toString()))

    override suspend fun unreconciledLegacyIds(): Pair<Set<String>, Set<String>>? {
        if (dao.meta(META_LEGACY_RECONCILED) != null) return null
        val maxSection = dao.meta(AppDatabase.META_LEGACY_MAX_SECTION)?.toLongOrNull() ?: 0L
        val maxItem = dao.meta(AppDatabase.META_LEGACY_MAX_ITEM)?.toLongOrNull() ?: 0L
        return (1..maxSection).mapTo(HashSet()) { LegacyUuid.section(it) } to
            (1..maxItem).mapTo(HashSet()) { LegacyUuid.item(it) }
    }

    override suspend fun markLegacyReconciled() =
        dao.setMeta(SyncMeta(META_LEGACY_RECONCILED, Instant.now().toString()))

    /** Sign-out: keep the notes, forget the server mapping, the cursor and the spaces. */
    suspend fun forgetServer() = db.withTransaction {
        dao.forgetSectionSync()
        dao.forgetItemSync()
        dao.clearSpaces()
        dao.deleteMeta(META_CURSOR)
    }

    private companion object {
        const val META_CURSOR = "cursor"
        const val META_LEGACY_RECONCILED = "legacy_reconciled_at"

        /** Well under SQLite's bound-variable limit. */
        const val CHUNK = 500
    }
}

/** Never carries rev — the server's trigger owns it. */
fun Section.toRow(): SectionRow = SectionRow(
    id = uuid,
    spaceId = requireNotNull(spaceId) { "section $id has no space yet" },
    name = name,
    color = color,
    emoji = emoji,
    sortKey = sortKey,
    createdAt = createdAt,
    deletedAt = null,
)

/** Never carries rev or created_by — the server's triggers own both. */
fun Item.toRow(sectionUuid: String): ItemRow = ItemRow(
    id = uuid,
    sectionId = sectionUuid,
    text = text,
    isChecked = isChecked,
    sortKey = sortKey,
    createdAt = createdAt,
    linkUrl = linkUrl,
    linkTitle = linkTitle,
    linkImageUrl = linkImageUrl,
    linkFetchFailed = linkFetchFailed,
    dueAt = dueAt,
    repeatWeeks = repeatWeeks,
    deletedAt = null,
)
