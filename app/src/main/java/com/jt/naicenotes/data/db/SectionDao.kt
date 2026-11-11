package com.jt.naicenotes.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.jt.naicenotes.data.entity.Section
import com.jt.naicenotes.data.sync.rekeyForOrder
import kotlinx.coroutines.flow.Flow

@Dao
interface SectionDao {

    @Query("SELECT * FROM sections ORDER BY $ITEM_ORDER")
    fun observeAll(): Flow<List<Section>>

    @Query("SELECT * FROM sections WHERE id = :id")
    suspend fun byId(id: Long): Section?

    @Query("SELECT COUNT(*) FROM sections")
    suspend fun count(): Int

    @Query("SELECT sortKey FROM sections ORDER BY sortKey DESC LIMIT 1")
    suspend fun lastKey(): String?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(section: Section): Long

    /** Everything the section dialog edits, in one write. */
    @Query(
        "UPDATE sections SET name = :name, emoji = :emoji, color = :color, " +
            "remoteKind = :remoteKind, $TOUCH WHERE id = :id",
    )
    suspend fun setDetails(id: Long, name: String, emoji: String?, color: Int, remoteKind: String?)

    @Query("UPDATE sections SET sortKey = :sortKey, $TOUCH WHERE id = :id")
    suspend fun setSortKey(id: Long, sortKey: String)

    @Query("SELECT id, sortKey FROM sections WHERE id IN (:ids)")
    suspend fun keysFor(ids: List<Long>): List<IdKey>

    /** Same minimal rewrite as [ItemDao.reorder]: a dragged tile is one row written. */
    @Transaction
    suspend fun reorder(newOrder: List<Long>) {
        val keys = keysFor(newOrder).associate { it.id to it.sortKey }
        val ids = newOrder.filter { it in keys }
        rekeyForOrder(ids.map { keys.getValue(it) }).forEachIndexed { i, key ->
            if (key != null) setSortKey(ids[i], key)
        }
    }

    /**
     * Clears [remoteKind] everywhere except [exceptId]. There's no unique index backing
     * "only one Claude section" — the invariant is kept by calling this before setting a
     * new one, so a stray second row can never accumulate through the UI. A section that
     * loses the flag becomes an ordinary one, so it's marked for upload.
     */
    @Query("UPDATE sections SET remoteKind = NULL, $TOUCH WHERE remoteKind = :remoteKind AND id != :exceptId")
    suspend fun clearRemoteKind(remoteKind: String, exceptId: Long)

    /** Cuts a section loose from its server row; see NotesRepository.updateSection. */
    @Query("UPDATE sections SET uuid = :uuid, spaceId = NULL, rev = NULL, $TOUCH WHERE id = :id")
    suspend fun detach(id: Long, uuid: String)

    @Query("DELETE FROM sections WHERE id = :id")
    suspend fun deleteById(id: Long)
}
