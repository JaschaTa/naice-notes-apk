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

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun getById(id: Long): Item?

    @Query("SELECT * FROM items WHERE linkUrl IS NOT NULL AND linkTitle IS NULL")
    suspend fun listLinksMissingPreview(): List<Item>

    @Query("UPDATE items SET linkTitle = :title, linkImageUrl = :imageUrl WHERE id = :id")
    suspend fun setLinkPreview(id: Long, title: String?, imageUrl: String?)

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
}
