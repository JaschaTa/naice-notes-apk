package com.jt.naicenotes.data.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Server row shapes, mirroring pwa/src/data/model.ts. Times the clients own (created_at,
// due_at) are epoch millis; deleted_at is the server's timestamptz as an ISO string.
//
// Encoded with `encodeDefaults = false`, so the two defaulted fields — rev and created_by —
// vanish from a push when null, which is how the "never send rev or created_by" rule in the
// sync contract is kept. Every other field is always sent, null included: an upsert carrying
// `deleted_at: null` is what revives a tombstone on undo.

@Serializable
data class SpaceRow(
    val id: String,
    val name: String,
    val kind: String,
    @SerialName("created_by") val createdBy: String,
) {
    val isPersonal: Boolean get() = kind == "personal"
}

@Serializable
data class SectionRow(
    val id: String,
    @SerialName("space_id") val spaceId: String,
    val name: String,
    val color: Int,
    val emoji: String?,
    @SerialName("sort_key") val sortKey: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("deleted_at") val deletedAt: String?,
    val rev: Long? = null,
)

@Serializable
data class ItemRow(
    val id: String,
    @SerialName("section_id") val sectionId: String,
    val text: String,
    @SerialName("is_checked") val isChecked: Boolean,
    @SerialName("sort_key") val sortKey: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("created_by") val createdBy: String? = null,
    @SerialName("link_url") val linkUrl: String?,
    @SerialName("link_title") val linkTitle: String?,
    @SerialName("link_image_url") val linkImageUrl: String?,
    @SerialName("link_fetch_failed") val linkFetchFailed: Boolean,
    @SerialName("due_at") val dueAt: Long?,
    @SerialName("repeat_weeks") val repeatWeeks: Int?,
    @SerialName("deleted_at") val deletedAt: String?,
    val rev: Long? = null,
)

/** A dirty local row on its way out, with the version it had when read. */
data class Outgoing<T>(val row: T, val version: Int)

/**
 * The server said something. [permanent] means the same request can never succeed (a refused
 * row); otherwise it's the network, the server, or an expired session, and the outbox waits.
 */
class RemoteException(message: String, val permanent: Boolean) : Exception(message)

/** No session, or the refresh token was refused. Local data is untouched; sign in again. */
class SignedOutException : Exception("Signed out")
