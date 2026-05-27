package com.jt.naicenotes.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "items",
    foreignKeys = [
        ForeignKey(
            entity = Section::class,
            parentColumns = ["id"],
            childColumns = ["sectionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("sectionId")],
)
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sectionId: Long,
    val text: String,
    val isChecked: Boolean = false,
    val position: Int,
    val createdAt: Long = System.currentTimeMillis(),
)
