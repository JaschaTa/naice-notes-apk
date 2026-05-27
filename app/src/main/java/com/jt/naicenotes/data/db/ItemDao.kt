package com.jt.naicenotes.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jt.naicenotes.data.entity.Item
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {

    @Query("SELECT * FROM items WHERE sectionId = :sectionId ORDER BY position ASC, createdAt ASC")
    fun observeBySection(sectionId: Long): Flow<List<Item>>

    @Query("SELECT * FROM items WHERE sectionId = :sectionId ORDER BY position ASC, createdAt ASC")
    suspend fun listBySection(sectionId: Long): List<Item>

    @Query("UPDATE items SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)

    @Query("SELECT COALESCE(MAX(position), -1) FROM items WHERE sectionId = :sectionId")
    suspend fun maxPositionInSection(sectionId: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: Item): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<Item>): List<Long>

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
