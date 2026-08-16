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
     */
    val remoteKind: String? = null,
) {
    val isInbox: Boolean get() = remoteKind == REMOTE_KIND_INBOX

    companion object {
        /** Pushes to the vault task inbox. */
        const val REMOTE_KIND_INBOX = "inbox"
    }
}
