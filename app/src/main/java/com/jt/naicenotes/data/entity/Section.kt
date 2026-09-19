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
    /**
     * Where items added to this section get sent, or null for an ordinary section — which
     * is every section that existed before this was introduced. Putting the integration on
     * the section rather than the item means each add path (composer, share target, widget
     * quick-add) needs no knowledge of it.
     *
     * At most one section carries a given kind; [com.jt.naicenotes.data.repo.NotesRepository
     * .setSectionRemoteKind] enforces that rather than a unique index.
     */
    val remoteKind: String? = null,
    /**
     * Optional pictogram shown on the section tile, in the app rail and in the widget header.
     * Null for every section that predates this and for anyone who doesn't want one — see
     * [glyph] for the fallback. Stored as the emoji's own characters rather than a code point
     * so it survives round-tripping through RemoteViews' `setTextViewText` unchanged.
     */
    val emoji: String? = null,
) {
    /** The one section that receives notes sent from the composer's Claude checkbox. */
    val isClaudeSection: Boolean get() = remoteKind == REMOTE_KIND_CLAUDE

    /**
     * What to draw on the section's tile: the emoji if one is set, otherwise the name's first
     * letter. Callers must also honour [hasEmoji] — a letter needs white-on-section-colour to
     * stay legible, whereas an emoji is its own multicoloured glyph and wants a light tint
     * behind it instead.
     */
    val glyph: String
        get() = emoji?.takeIf { it.isNotBlank() }
            ?: name.trim().firstOrNull()?.uppercase()
            ?: "•"

    val hasEmoji: Boolean get() = !emoji.isNullOrBlank()

    companion object {
        /**
         * Pushes to the vault task inbox. The stored value stays `"inbox"` from when this
         * was a per-section setting — changing it would need a migration to keep already
         * designated sections working, and nothing outside the DB reads the literal.
         */
        const val REMOTE_KIND_CLAUDE = "inbox"
    }
}
