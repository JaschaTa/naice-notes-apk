package com.jt.naicenotes.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sections")
data class Section(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int,
    val position: Int,
    val createdAt: Long = System.currentTimeMillis(),
)
