package com.jt.naicenotes.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jt.naicenotes.data.entity.Item
import com.jt.naicenotes.data.entity.PendingDelete
import com.jt.naicenotes.data.entity.Section
import com.jt.naicenotes.data.entity.SyncMeta
import com.jt.naicenotes.data.entity.SyncSpace

/**
 * Sync-side reads and writes. Nothing here uses [TOUCH]: a row the server just sent, or one
 * just pushed, is by definition not a local change waiting to go out.
 */
@Dao
interface SyncDao {

    // ── Delete outbox (also written by the repository) ──

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun queueDeletes(rows: List<PendingDelete>)

    @Query(
        "INSERT OR REPLACE INTO pending_deletes (uuid, kind) " +
            "SELECT uuid, 'item' FROM items WHERE sectionId = :sectionId AND isChecked = 1",
    )
    suspend fun queueCheckedItemDeletes(sectionId: Long)

    @Query("INSERT OR REPLACE INTO pending_deletes (uuid, kind) SELECT uuid, 'item' FROM items WHERE sectionId = :sectionId")
    suspend fun queueAllItemDeletes(sectionId: Long)

    @Query("DELETE FROM pending_deletes WHERE uuid = :uuid")
    suspend fun unqueueDelete(uuid: String)

    @Query("SELECT * FROM pending_deletes")
    suspend fun pendingDeletes(): List<PendingDelete>

    @Query("DELETE FROM pending_deletes WHERE uuid IN (:uuids)")
    suspend fun clearPendingDeletes(uuids: List<String>)

    // ── Push ──

    @Query("UPDATE sections SET spaceId = :spaceId WHERE spaceId IS NULL AND remoteKind IS NULL")
    suspend fun assignUnspacedSections(spaceId: String)

    @Query("SELECT * FROM sections WHERE dirty = 1 AND remoteKind IS NULL AND spaceId IS NOT NULL")
    suspend fun dirtySections(): List<Section>

    @Query(
        "SELECT i.*, s.uuid AS sectionUuid FROM items i JOIN sections s ON s.id = i.sectionId " +
            "WHERE i.dirty = 1 AND s.remoteKind IS NULL AND s.spaceId IS NOT NULL",
    )
    suspend fun dirtyItems(): List<ItemWithSectionUuid>

    @Query("UPDATE sections SET dirty = 0 WHERE uuid = :uuid AND version = :version")
    suspend fun markSectionPushed(uuid: String, version: Int)

    @Query("UPDATE items SET dirty = 0 WHERE uuid = :uuid AND version = :version")
    suspend fun markItemPushed(uuid: String, version: Int)

    // ── Pull ──

    @Query("SELECT * FROM sections WHERE uuid = :uuid")
    suspend fun sectionByUuid(uuid: String): Section?

    @Query("SELECT * FROM sections WHERE id = :id")
    suspend fun sectionById(id: Long): Section?

    @Query("SELECT * FROM items WHERE uuid = :uuid")
    suspend fun itemByUuid(uuid: String): Item?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSection(section: Section): Long

    @Update
    suspend fun updateSection(section: Section)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItem(item: Item): Long

    @Update
    suspend fun updateItem(item: Item)

    @Query("DELETE FROM sections WHERE id = :id")
    suspend fun deleteSection(id: Long)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun deleteItem(id: Long)

    /** Items go with their section through the foreign key's ON DELETE CASCADE. */
    @Query("DELETE FROM sections WHERE spaceId IN (:spaceIds) AND dirty = 0")
    suspend fun deleteCleanSectionsInSpaces(spaceIds: List<String>): Int

    @Query("SELECT uuid FROM sections WHERE uuid IN (:uuids)")
    suspend fun existingSectionUuids(uuids: List<String>): List<String>

    @Query("SELECT uuid FROM items WHERE uuid IN (:uuids)")
    suspend fun existingItemUuids(uuids: List<String>): List<String>

    @Query("SELECT uuid FROM sections WHERE rev IS NOT NULL AND dirty = 0 AND remoteKind IS NULL")
    suspend fun syncedCleanSectionUuids(): List<String>

    @Query(
        "SELECT i.uuid FROM items i JOIN sections s ON s.id = i.sectionId " +
            "WHERE i.rev IS NOT NULL AND i.dirty = 0 AND s.remoteKind IS NULL",
    )
    suspend fun syncedCleanItemUuids(): List<String>

    @Query("DELETE FROM sections WHERE uuid IN (:uuids)")
    suspend fun deleteSectionsByUuid(uuids: List<String>): Int

    @Query("DELETE FROM items WHERE uuid IN (:uuids)")
    suspend fun deleteItemsByUuid(uuids: List<String>): Int

    // ── Spaces & meta ──

    @Query("SELECT * FROM sync_spaces")
    suspend fun spaces(): List<SyncSpace>

    @Query("SELECT id FROM sync_spaces WHERE kind = 'personal' LIMIT 1")
    suspend fun personalSpaceId(): String?

    @Query("DELETE FROM sync_spaces")
    suspend fun clearSpaces()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpaces(spaces: List<SyncSpace>)

    @Query("SELECT value FROM sync_meta WHERE `key` = :key")
    suspend fun meta(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setMeta(meta: SyncMeta)

    @Query("DELETE FROM sync_meta WHERE `key` = :key")
    suspend fun deleteMeta(key: String)

    // ── Sign-out ──

    /**
     * Forgets which server rows this device mirrors, keeping every note. Signing in again
     * re-files the sections under the personal space and pushes everything; the server
     * de-duplicates by uuid, so nothing is copied twice.
     */
    @Query("UPDATE sections SET spaceId = NULL, rev = NULL, dirty = 1")
    suspend fun forgetSectionSync()

    @Query("UPDATE items SET rev = NULL, dirty = 1")
    suspend fun forgetItemSync()
}

data class ItemWithSectionUuid(
    @Embedded val item: Item,
    val sectionUuid: String,
)
