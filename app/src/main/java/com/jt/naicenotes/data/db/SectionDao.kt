package com.jt.naicenotes.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jt.naicenotes.data.entity.Section
import kotlinx.coroutines.flow.Flow

@Dao
interface SectionDao {

    @Query("SELECT * FROM sections ORDER BY position ASC, createdAt ASC")
    fun observeAll(): Flow<List<Section>>

    @Query("SELECT * FROM sections WHERE id = :id")
    suspend fun getById(id: Long): Section?

    @Query("SELECT COUNT(*) FROM sections")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(position), -1) FROM sections")
    suspend fun maxPosition(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(section: Section): Long

    @Update
    suspend fun update(section: Section)

    @Query("UPDATE sections SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)

    @Delete
    suspend fun delete(section: Section)
}
