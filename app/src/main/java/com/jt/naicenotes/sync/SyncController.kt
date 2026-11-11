package com.jt.naicenotes.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jt.naicenotes.NaiceNotesApp
import com.jt.naicenotes.data.db.AppDatabase
import com.jt.naicenotes.data.entity.Section
import com.jt.naicenotes.data.sync.AuthStore
import com.jt.naicenotes.data.sync.RoomSyncStore
import com.jt.naicenotes.data.sync.SignedOutException
import com.jt.naicenotes.data.sync.SupabaseClient
import com.jt.naicenotes.data.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

sealed interface SyncStatus {
    /** This build has no SUPABASE_URL / SUPABASE_ANON_KEY. */
    data object Unconfigured : SyncStatus
    data object SignedOut : SyncStatus
    data class Idle(val email: String, val lastSyncedAt: Long?, val refused: Int = 0) : SyncStatus
    data class Syncing(val email: String) : SyncStatus
    data class Failed(val email: String, val message: String, val lastSyncedAt: Long?) : SyncStatus
}

/**
 * Owns the sync lifecycle: when a run happens, that only one runs at a time, and what the UI
 * shows. The engine itself knows nothing about Android.
 *
 * Triggers — Android has no realtime channel, so remote edits arrive on the next of these:
 * - every local write ([requestSoon], debounced, so a burst of edits is one run);
 * - the app coming to the foreground, and every [FOREGROUND_INTERVAL_MS] while it stays there
 *   (wired in MainActivity);
 * - [SyncWorker], every 15 minutes in the background, which also keeps the widget fresh.
 *
 * Runs are folded: requests that arrive while one is running collapse into a single follow-up.
 */
class SyncController(
    context: Context,
    db: AppDatabase,
    private val scope: CoroutineScope,
    url: String,
    anonKey: String,
    /** Called after a pull changed local rows — the app re-renders the widget there. */
    private val onPulled: suspend (applied: Int, linksToFetch: Boolean) -> Unit,
) {
    private val authStore = AuthStore(context)
    private val client = SupabaseClient(url, anonKey, authStore)
    private val store = RoomSyncStore(db)
    private val engine = SyncEngine(store, client) { Log.w(TAG, it) }

    private val runLock = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var debounce: Job? = null
    private var lastSyncedAt: Long? = null

    private val _status = MutableStateFlow(initialStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    val isConfigured: Boolean get() = client.isConfigured

    val hasSession: Boolean get() = client.session != null

    init {
        scope.launch {
            for (request in requests) runOnce()
        }
    }

    private fun initialStatus(): SyncStatus = when {
        !client.isConfigured -> SyncStatus.Unconfigured
        else -> client.session?.let { SyncStatus.Idle(it.email, null) } ?: SyncStatus.SignedOut
    }

    /** After a local write: wait for the burst to settle, then sync. */
    fun requestSoon() {
        if (client.session == null) return
        debounce?.cancel()
        debounce = scope.launch {
            delay(LOCAL_WRITE_DEBOUNCE_MS)
            requests.trySend(Unit)
        }
    }

    fun requestNow() {
        if (client.session == null) return
        requests.trySend(Unit)
    }

    /** One run, awaited — for the background worker. */
    suspend fun syncNow() = runOnce()

    private suspend fun runOnce() = runLock.withLock {
        val session = client.session ?: run {
            if (client.isConfigured) _status.value = SyncStatus.SignedOut
            return@withLock
        }
        _status.value = SyncStatus.Syncing(session.email)
        try {
            val outcome = engine.sync()
            lastSyncedAt = System.currentTimeMillis()
            _status.value = SyncStatus.Idle(session.email, lastSyncedAt, outcome.refused)
            if (outcome.applied > 0 || outcome.linksToFetch) onPulled(outcome.applied, outcome.linksToFetch)
            Log.i(TAG, "Sync ok — ${outcome.applied} applied, ${outcome.refused} refused")
        } catch (e: SignedOutException) {
            _status.value = SyncStatus.SignedOut
        } catch (e: Exception) {
            // Inline, not as a throwable arg — see CLAUDE.md on logcat dropping stack traces.
            Log.w(TAG, "Sync failed — ${e::class.java.simpleName}: ${e.message}")
            _status.value = SyncStatus.Failed(session.email, e.message ?: e::class.java.simpleName, lastSyncedAt)
        }
    }

    // ── Sharing ──
    //
    // Online-only server calls, unlike every other change: the rules (owner-only delete,
    // invites) live in Postgres. Each one syncs first, so the server has every local edit, and
    // after, so the moved section and its new space show up straight away. Errors are thrown
    // to the dialog rather than queued.

    private val syncDao = db.syncDao()

    /** The spaces from the last pull — enough to tell private, shared-by-me and shared-with-me apart. */
    val spaces = syncDao.observeSpaces()

    val userId: String? get() = client.session?.userId

    val email: String get() = client.session?.email.orEmpty()

    suspend fun people(): List<com.jt.naicenotes.data.sync.SpacePerson> = client.spacePeople()

    suspend fun share(section: Section, email: String) {
        syncOrThrow()
        val to = email.trim().lowercase()
        client.shareSection(section.uuid, to)
        // Best-effort, like the PWA: the share has happened whether or not the email lands.
        runCatching { client.sendInvite(section.uuid, to) }
            .onFailure { Log.w(TAG, "Invite email failed — ${it::class.java.simpleName}: ${it.message}") }
        syncOrThrow()
    }

    suspend fun unshare(section: Section) {
        syncOrThrow()
        client.unshareSection(section.uuid)
        syncDao.personalSpaceId()?.let { syncDao.setSectionSpace(section.uuid, it) }
        syncOrThrow()
    }

    suspend fun leave(section: Section) {
        val space = section.spaceId ?: return
        syncOrThrow()
        client.leaveSpace(space)
        syncOrThrow() // the space is gone from the listing, so the pull drops the section here
    }

    /** Like [runOnce], but a failure reaches the caller instead of only the status line. */
    private suspend fun syncOrThrow() {
        runOnce()
        val s = _status.value
        if (s is SyncStatus.Failed) throw com.jt.naicenotes.data.sync.RemoteException(s.message, permanent = false)
        if (s is SyncStatus.SignedOut) throw SignedOutException()
    }

    // ── Account ──

    suspend fun requestCode(email: String) = client.requestCode(email.trim().lowercase())

    suspend fun verifyCode(email: String, code: String) {
        val session = client.verifyCode(email.trim().lowercase(), code.trim())
        _status.value = SyncStatus.Idle(session.email, null)
        requestNow()
    }

    /** Notes stay on the phone; only the link to the server is forgotten. */
    suspend fun signOut() = runLock.withLock {
        client.signOut()
        store.forgetServer()
        lastSyncedAt = null
        _status.value = SyncStatus.SignedOut
    }

    companion object {
        private const val TAG = "NaiceNotes"
        private const val LOCAL_WRITE_DEBOUNCE_MS = 1_500L
        const val FOREGROUND_INTERVAL_MS = 60_000L

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("naice-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

/** The 15-minute background pull. Same in-process controller, so it can't overlap a foreground run. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as NaiceNotesApp).sync.syncNow()
        return Result.success()
    }
}
