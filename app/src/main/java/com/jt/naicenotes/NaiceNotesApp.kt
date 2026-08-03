package com.jt.naicenotes

import android.app.Application
import android.util.Log
import com.jt.naicenotes.data.db.AppDatabase
import com.jt.naicenotes.data.remote.LinkPreviewClient
import com.jt.naicenotes.data.remote.PermanentFetchException
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

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.get(this)
        repository = NotesRepository(
            db = db,
            onChange = { ClassicWidgetRenderer.renderAll(applicationContext) },
            onLinkDetected = ::fetchLinkPreview,
        )
        appScope.launch {
            seedIfEmpty()
            retryMissingLinkPreviews()
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
                    Log.w(TAG, "Link preview failed for $url", error)
                    // Permanent failures stop retrying; the item falls back to its
                    // URL-derived label. Transient ones stay eligible for next launch.
                    if (error is PermanentFetchException) {
                        repository.markLinkFetchFailed(itemId)
                    }
                }
        }
    }

    /** Covers links shared while offline, or sites that were briefly failing. */
    private suspend fun retryMissingLinkPreviews() {
        repository.linksMissingPreview()
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
