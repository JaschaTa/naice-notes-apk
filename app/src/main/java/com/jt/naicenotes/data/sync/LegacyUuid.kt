package com.jt.naicenotes.data.sync

import java.security.MessageDigest

/**
 * Sync identity for rows that existed before sync (schema v7 and earlier).
 *
 * UUIDv5 of `section:<id>` / `item:<id>` under a fixed namespace — the same derivation
 * `pwa/scripts/import-android-snapshot.mjs` used when the S24's notes were imported into the
 * personal space on 2026-11-11. Because the migration computes the same ids, the first sync
 * adopts those server rows instead of uploading a second copy of every note.
 *
 * Rows created after the migration get random UUIDs, so a v5 id under this namespace always
 * means "an Android row from before sync" — which is what lets the first sync recognise notes
 * that were deleted on the phone after the import (see [SyncEngine]).
 */
object LegacyUuid {
    private const val NAMESPACE = "6b1f8f0e-6a52-4e43-9d3a-5f0c2b9a7e11"

    fun section(localId: Long): String = v5("section:$localId")

    fun item(localId: Long): String = v5("item:$localId")

    private val namespaceBytes: ByteArray = NAMESPACE.replace("-", "")
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun v5(name: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(namespaceBytes)
        sha1.update(name.toByteArray(Charsets.UTF_8))
        val h = sha1.digest()
        h[6] = ((h[6].toInt() and 0x0f) or 0x50).toByte()
        h[8] = ((h[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = h.take(16).joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
            "${hex.substring(16, 20)}-${hex.substring(20)}"
    }
}
