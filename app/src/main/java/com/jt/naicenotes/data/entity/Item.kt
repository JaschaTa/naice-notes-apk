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
    /** Set when the item's text is (or contains) a URL — drives the link-card rendering. */
    val linkUrl: String? = null,
    /** Fetched from the page's Open Graph tags; null until the fetch lands, or if it failed. */
    val linkTitle: String? = null,
    val linkImageUrl: String? = null,
) {
    val isLink: Boolean get() = linkUrl != null

    /** What to show as the item's main line: the page title once known, else the raw text. */
    val displayText: String get() = linkTitle?.takeIf { it.isNotBlank() } ?: text

    /** Bare host for the card's second line, e.g. "chefkoch.de". */
    val linkDomain: String?
        get() = linkUrl
            ?.substringAfter("://", "")
            ?.substringBefore('/')
            ?.substringBefore(':')
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
}
