package com.jt.naicenotes.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The delete outbox. Local deletes stay hard deletes — so no query anywhere needs a
 * `deletedAt IS NULL` filter — and each one leaves a row here until the server has the
 * tombstone. Restoring a note (undo) removes its row again, so the server copy is revived
 * by the next upsert rather than deleted and re-created.
 */
@Entity(tableName = "pending_deletes")
data class PendingDelete(
    @PrimaryKey val uuid: String,
    /** [KIND_SECTION] or [KIND_ITEM]. */
    val kind: String,
) {
    companion object {
        const val KIND_SECTION = "section"
        const val KIND_ITEM = "item"
    }
}

/** A server space this device has seen in its last pull. Replaced wholesale every pull. */
@Entity(tableName = "sync_spaces")
data class SyncSpace(
    @PrimaryKey val id: String,
    val name: String,
    /** `personal` or `shared`. */
    val kind: String,
    /** The owner: whoever shared the section in it, or the user of a personal space. */
    val createdBy: String,
)

/** Small key/value store for the sync cursor and one-off flags, kept in the same DB transaction. */
@Entity(tableName = "sync_meta")
data class SyncMeta(
    @PrimaryKey val key: String,
    val value: String,
)
