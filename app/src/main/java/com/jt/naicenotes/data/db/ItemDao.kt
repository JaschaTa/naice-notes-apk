package com.jt.naicenotes.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.jt.naicenotes.data.entity.Item
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {

    @Query("SELECT * FROM items WHERE sectionId = :sectionId ORDER BY position ASC, createdAt ASC")
    fun observeBySection(sectionId: Long): Flow<List<Item>>

    @Query("SELECT * FROM items WHERE sectionId = :sectionId ORDER BY position ASC, createdAt ASC")
    suspend fun listBySection(sectionId: Long): List<Item>

    @Query(
        "SELECT * FROM items WHERE linkUrl IS NOT NULL AND linkTitle IS NULL " +
            "AND linkFetchFailed = 0",
    )
    suspend fun listLinksMissingPreview(): List<Item>

    @Query(
        "UPDATE items SET linkTitle = :title, linkImageUrl = :imageUrl, " +
            "linkFetchFailed = 0 WHERE id = :id",
    )
    suspend fun setLinkPreview(id: Long, title: String?, imageUrl: String?)

    @Query("UPDATE items SET linkFetchFailed = 1 WHERE id = :id")
    suspend fun markLinkFetchFailed(id: Long)

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun byId(id: Long): Item?

    @Query("UPDATE items SET pushedAt = :at WHERE id = :id")
    suspend fun markPushed(id: Long, at: Long)

    @Query("UPDATE items SET dueAt = :dueAt, repeatWeeks = :repeatWeeks WHERE id = :id")
    suspend fun setSchedule(id: Long, dueAt: Long?, repeatWeeks: Int?)

    /**
     * Notes in a remote-backed section that never reached the inbox — the app was offline at
     * add time, or the call failed. Ordered oldest-first so a backlog drains in the order it
     * was captured. No `linkFetchFailed`-style give-up flag exists here on purpose.
     */
    @Query(
        "SELECT i.* FROM items i JOIN sections s ON s.id = i.sectionId " +
            "WHERE s.remoteKind IS NOT NULL AND i.pushedAt IS NULL " +
            "ORDER BY i.createdAt ASC",
    )
    suspend fun listUnpushedInRemoteSections(): List<Item>

    /**
     * Every item counted per section, split by checked state and due date. Feeds the rail
     * badges, the header counts and the clear dialog's totals from one aggregate.
     *
     * Grouping on `dueAt` rather than filtering by it keeps this free of the clock: a `:now`
     * parameter would bind once and the Flow would go on answering for that instant forever.
     * [com.jt.naicenotes.data.util.countsBySection] applies the clock instead. Every
     * unscheduled item still collapses into one bucket per section and checked state, so this
     * stays a real aggregate rather than a row per item.
     */
    @Query("SELECT sectionId, isChecked, dueAt, COUNT(*) AS count FROM items GROUP BY sectionId, isChecked, dueAt")
    fun observeItemBuckets(): Flow<List<SectionItemBucket>>

    @Query("UPDATE items SET sectionId = :sectionId, position = :position WHERE id = :id")
    suspend fun setSection(id: Long, sectionId: Long, position: Int)

    /**
     * Move an item into another section, landing at the top the way a freshly added item
     * would. Transactional for the same reason [insertAtTop] is: a half-applied shift leaves
     * the target section's order scrambled.
     */
    @Transaction
    suspend fun moveToSectionTop(id: Long, targetSectionId: Long) {
        shiftPositions(targetSectionId, 1)
        setSection(id, targetSectionId, 0)
    }

    @Query("UPDATE items SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)

    @Query("UPDATE items SET position = position + :delta WHERE sectionId = :sectionId")
    suspend fun shiftPositions(sectionId: Long, delta: Int)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: Item): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<Item>): List<Long>

    /**
     * Insert at the head of the section. Every existing row shifts down one so the
     * new row can own position 0; the caller's `position` is ignored. Transactional —
     * a half-applied shift would scramble the section's order.
     */
    @Transaction
    suspend fun insertAtTop(item: Item): Long {
        shiftPositions(item.sectionId, 1)
        return insert(item.copy(position = 0))
    }

    /**
     * Insert a batch at the head, keeping the batch's own order (its first element
     * ends up topmost). Existing rows shift down by the batch size.
     */
    @Transaction
    suspend fun insertAllAtTop(sectionId: Long, newItems: List<Item>): List<Long> {
        if (newItems.isEmpty()) return emptyList()
        shiftPositions(sectionId, newItems.size)
        return insertAll(newItems.mapIndexed { index, item -> item.copy(position = index) })
    }

    @Update
    suspend fun update(item: Item)

    @Query("UPDATE items SET isChecked = :checked WHERE id = :id")
    suspend fun setChecked(id: Long, checked: Boolean)

    @Query("UPDATE items SET isChecked = NOT isChecked WHERE id = :id")
    suspend fun toggleById(id: Long)

    @Delete
    suspend fun delete(item: Item)

    @Query("DELETE FROM items WHERE sectionId = :sectionId AND isChecked = 1")
    suspend fun deleteCheckedInSection(sectionId: Long)

    @Query("DELETE FROM items WHERE sectionId = :sectionId")
    suspend fun deleteAllInSection(sectionId: Long)
}

/** One row of [ItemDao.observeItemBuckets]: items in a section sharing a state and due date. */
data class SectionItemBucket(
    val sectionId: Long,
    val isChecked: Boolean,
    val dueAt: Long?,
    val count: Int,
)
