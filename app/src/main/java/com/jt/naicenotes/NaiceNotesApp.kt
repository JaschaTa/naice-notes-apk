package com.jt.naicenotes

import android.app.Application
import android.util.Log
import com.jt.naicenotes.data.db.AppDatabase
import com.jt.naicenotes.data.entity.Item
import com.jt.naicenotes.data.remote.InboxPayload
import com.jt.naicenotes.data.remote.InboxPushClient
import com.jt.naicenotes.data.remote.LinkPreviewClient
import com.jt.naicenotes.data.remote.PermanentFetchException
import com.jt.naicenotes.data.remote.inboxDedupeKey
import com.jt.naicenotes.data.remote.inboxTitle
import com.jt.naicenotes.data.repo.NotesRepository
import com.jt.naicenotes.widget.ClassicWidgetRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NaiceNotesApp : Application() {

    lateinit var repository: NotesRepository
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val linkPreviews = LinkPreviewClient()

    private val inboxPush = InboxPushClient(
        url = BuildConfig.INBOX_PUSH_URL,
        jwtSecret = BuildConfig.INBOX_PUSH_JWT_SECRET,
    )

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.get(this)
        repository = NotesRepository(
            db = db,
            onChange = { ClassicWidgetRenderer.renderAll(applicationContext) },
            onLinkDetected = ::fetchLinkPreview,
            onInboxItem = ::pushToInbox,
        )
        appScope.launch {
            seedIfEmpty()
            retryMissingLinkPreviews()
            retryPendingInboxPushes()
        }
    }

    /**
     * Best-effort enrichment: on failure the item keeps showing its raw URL and gets
     * another attempt on next launch via [retryMissingLinkPreviews].
     */
    private fun fetchLinkPreview(itemId: Long, url: String) {
        appScope.launch {
            linkPreviews.fetch(url)
                .onSuccess { preview ->
                    repository.setLinkPreview(itemId, preview.title, preview.imageUrl)
                }
                .onFailure { error ->
                    // Exception inline rather than as a throwable arg: logcat sometimes
                    // drops the stack-trace continuation lines under a tag filter, which
                    // makes the actual cause invisible exactly when it's needed.
                    Log.w(
                        TAG,
                        "Link preview failed for $url — " +
                            "${error::class.java.simpleName}: ${error.message}",
                    )
                    // Permanent failures stop retrying; the item falls back to its
                    // URL-derived label. Transient ones stay eligible for next launch.
                    if (error is PermanentFetchException) {
                        repository.markLinkFetchFailed(itemId)
                    }
                }
        }
    }

    /**
     * Send a note to the vault task inbox. Best-effort like [fetchLinkPreview], but with no
     * permanent-failure path: if this never lands the note keeps its NULL `pushedAt` and gets
     * picked up again by [retryPendingInboxPushes] on the next launch. Dropping a captured
     * task would be worse than retrying a hopeless one.
     */
    private fun pushToInbox(item: Item, sectionName: String) {
        if (!inboxPush.isConfigured) return
        appScope.launch {
            val payload = InboxPayload(
                title = inboxTitle(item),
                text = item.text,
                section = sectionName,
                createdAt = item.createdAt,
                linkUrl = item.linkUrl,
                clientId = "naice-notes/${BuildConfig.VERSION_NAME}",
                // Derived from immutable fields only, so a retry can't create a second issue.
                dedupeKey = inboxDedupeKey(item.id, item.createdAt),
            )
            inboxPush.push(payload)
                .onSuccess { iid ->
                    repository.markPushed(item.id)
                    Log.i(TAG, "Pushed item ${item.id} to inbox${iid?.let { " as #$it" }.orEmpty()}")
                }
                .onFailure { error ->
                    // Exception inline rather than as a throwable arg, per fetchLinkPreview.
                    Log.w(
                        TAG,
                        "Inbox push failed for item ${item.id} — " +
                            "${error::class.java.simpleName}: ${error.message}",
                    )
                }
        }
    }

    /** Covers notes captured while offline, or a webhook that was briefly unreachable. */
    private suspend fun retryPendingInboxPushes() {
        if (!inboxPush.isConfigured) return
        val pending = repository.pendingInboxItems()
        if (pending.isEmpty()) return
        Log.i(TAG, "Inbox push retry queue: ${pending.size}")
        pending
            .take(MAX_RETRIES_PER_LAUNCH)
            .forEach { pushToInbox(it.item, it.sectionName) }
    }

    /** Covers links shared while offline, or sites that were briefly failing. */
    private suspend fun retryMissingLinkPreviews() {
        val pending = repository.linksMissingPreview()
        Log.i(TAG, "Link preview retry queue: ${pending.size}")
        pending
            .take(MAX_RETRIES_PER_LAUNCH)
            .forEach { item -> item.linkUrl?.let { fetchLinkPreview(item.id, it) } }
    }

    private suspend fun seedIfEmpty() {
        if (repository.sectionCount() == 0) {
            repository.addSection(name = "Shopping", color = SEED_COLOR_SHOPPING)
            repository.addSection(name = "Work", color = SEED_COLOR_WORK)
        }
    }

    companion object {
        private const val TAG = "NaiceNotes"
        private const val SEED_COLOR_SHOPPING = 0xFF4CAF50.toInt()
        private const val SEED_COLOR_WORK = 0xFF2196F3.toInt()

        /** Keep a cold start cheap even if a lot of links failed while offline. */
        private const val MAX_RETRIES_PER_LAUNCH = 10
    }
}
