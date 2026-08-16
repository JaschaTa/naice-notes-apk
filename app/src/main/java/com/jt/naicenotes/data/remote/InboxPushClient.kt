package com.jt.naicenotes.data.remote

import com.jt.naicenotes.data.entity.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Pushes a newly created note to the vault's task inbox, which turns it into a GitLab
 * issue that `/process-tasks` later proposes as a real task.
 *
 * Shaped after [RecipeScanClient] — same OkHttp/Result/blank-config idiom — with two
 * differences: a JSON body instead of multipart, and a per-request signed JWT rather than
 * a static secret header (see [JwtSigner]).
 *
 * Delivery is best-effort in the same spirit as link previews: a failure leaves
 * `items.pushedAt` NULL, the note stays put, and the next app launch retries. Unlike a
 * link preview there is no give-up flag — a task that never reached the inbox should keep
 * being retried rather than silently dropped.
 */
class InboxPushClient(
    private val url: String,
    private val jwtSecret: String,
) {

    // Shorter than the recipe scan's 60s: this is a few hundred bytes of text, and a
    // hung push shouldn't hold a coroutine open for a minute on a flaky mobile connection.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean get() = url.isNotBlank() && jwtSecret.isNotBlank()

    /** @return the inbox issue's iid when the workflow reports one. */
    suspend fun push(payload: InboxPayload): Result<Long?> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isConfigured) {
                throw IllegalStateException(
                    "Inbox push not configured. Add INBOX_PUSH_URL and " +
                        "INBOX_PUSH_JWT_SECRET to local.properties.",
                )
            }

            // Minted per call, never cached. A token stored during a failed push would
            // arrive expired on the retry — exactly the bug the retry path must not have.
            val token = JwtSigner.sign(
                secret = jwtSecret,
                issuer = ISSUER,
                nowMillis = System.currentTimeMillis(),
            )

            val request = Request.Builder()
                .url(url)
                .post(
                    json.encodeToString(InboxPayload.serializer(), payload)
                        .toRequestBody(JSON_MEDIA_TYPE.toMediaType()),
                )
                .addHeader("Authorization", "Bearer $token")
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}: ${raw.take(ERROR_SNIPPET)}")
                }
                // The workflow answers {"ok":true,"iid":N}. A duplicate short-circuits and
                // returns the *existing* iid, which still counts as delivered — that's the
                // whole point of the dedupe key surviving a retry.
                raw.takeIf { it.isNotBlank() }
                    ?.let { json.decodeFromString<PushResponse>(it) }
                    ?.iid
            }
        }
    }

    private companion object {
        const val ISSUER = "naice-notes"
        const val JSON_MEDIA_TYPE = "application/json"
        const val ERROR_SNIPPET = 200
    }
}

@Serializable
data class InboxPayload(
    /** Pre-derived so the issue title is decided by testable Kotlin, not workflow logic. */
    val title: String,
    val text: String,
    val section: String,
    val createdAt: Long,
    val linkUrl: String? = null,
    val clientId: String,
    val dedupeKey: String,
)

@Serializable
private data class PushResponse(val ok: Boolean = false, val iid: Long? = null)

/**
 * Turns a note into the one-line issue title. Reuses [Item.displayText], which already
 * prefers a fetched page title and otherwise derives a readable name from a URL slug —
 * worth having here because a link is pushed before its preview lands, so the raw text at
 * that moment is a bare URL with tracking parameters.
 */
fun inboxTitle(item: Item): String {
    val collapsed = item.displayText.replace(WHITESPACE_RUN, " ").trim()
    if (collapsed.isEmpty()) return FALLBACK_TITLE
    return if (collapsed.length <= MAX_TITLE_LENGTH) {
        collapsed
    } else {
        // Prefer cutting at a word boundary, but only if that keeps most of the budget —
        // a title ending mid-word reads worse than one a few characters short.
        val hardCut = collapsed.take(MAX_TITLE_LENGTH)
        val lastSpace = hardCut.lastIndexOf(' ')
        val body = if (lastSpace >= MIN_WORD_BOUNDARY) hardCut.take(lastSpace) else hardCut
        "${body.trimEnd()}…"
    }
}

/**
 * Stable per-item identity for the workflow's duplicate check. Derived only from fields
 * that never change after insert, so a retry — days later, after edits — produces the same
 * key and cannot create a second issue.
 */
fun inboxDedupeKey(itemId: Long, createdAt: Long): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("naice-notes:$itemId:$createdAt".toByteArray())
    return "nn-" + digest.take(DEDUPE_BYTES).joinToString("") { "%02x".format(it) }
}

private val WHITESPACE_RUN = Regex("\\s+")
private const val FALLBACK_TITLE = "Untitled note"
private const val MAX_TITLE_LENGTH = 80
private const val MIN_WORD_BOUNDARY = 40
private const val DEDUPE_BYTES = 8
