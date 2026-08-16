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
    /**
     * Set once a fetch fails in a way retrying can't fix (site blocks us, page gone, no
     * metadata). Keeps the launch-time retry from hammering dead URLs forever.
     */
    val linkFetchFailed: Boolean = false,
    /**
     * When this note reached the vault task inbox, or null if it hasn't. Only meaningful in
     * a section whose `remoteKind` is set. Deliberately has no failure counterpart: unlike
     * a dead link, a task that never landed should keep being retried, so "not sent yet"
     * and "sending failed" are intentionally the same state.
     */
    val pushedAt: Long? = null,
) {
    val isLink: Boolean get() = linkUrl != null

    val isPushed: Boolean get() = pushedAt != null

    /**
     * The item's main line. Prefers the fetched page title; falls back to a readable name
     * derived from the URL slug, because some sites (Etsy, for one) block metadata fetches
     * outright and a raw URL with tracking parameters is unreadable in a list.
     */
    val displayText: String
        get() = linkTitle?.takeIf { it.isNotBlank() }
            ?: linkUrl?.let(::slugToTitle)
            ?: text

    /** Bare host for the card's second line, e.g. "chefkoch.de". */
    val linkDomain: String?
        get() = linkUrl
            ?.substringAfter("://", "")
            ?.substringBefore('/')
            ?.substringBefore(':')
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }

    /**
     * "…/listing/1878539213/tamperstation-aus-beton-fur?ga_order=…" → "Tamperstation aus
     * beton fur". Returns null when no path segment carries real words (e.g. Pinterest's
     * numeric pin ids), so the caller can fall back further.
     */
    private fun slugToTitle(url: String): String? {
        // Drop scheme *and* host — otherwise "https://www.kaufland.de/" treats the
        // hostname as a path segment and yields "Www.kaufland".
        val path = url.substringAfter("://", "")
            .substringAfter('/', "")
            .substringBefore('?')
            .substringBefore('#')
        val segment = path.split('/')
            .lastOrNull { seg -> seg.isNotBlank() && seg.any { it.isLetter() } }
            ?.takeIf { seg -> seg.count { it.isLetter() } >= MIN_SLUG_LETTERS }
            ?: return null

        return segment
            .substringBeforeLast('.')
            .replace('-', ' ')
            .replace('_', ' ')
            .trim()
            .takeIf { it.isNotBlank() }
            ?.replaceFirstChar { it.uppercase() }
    }

    private companion object {
        /** Below this, a segment is more likely an id or file extension than a name. */
        const val MIN_SLUG_LETTERS = 4
    }
}
