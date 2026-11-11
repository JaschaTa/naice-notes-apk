package com.jt.naicenotes.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.jt.naicenotes.data.entity.Item
import com.jt.naicenotes.data.sync.FractionalIndex
import com.jt.naicenotes.data.sync.rekeyForOrder
import kotlinx.coroutines.flow.Flow

/** Display order: sort key bytewise, then age, then uuid — the same tie-break as the PWA. */
const val ITEM_ORDER = "sortKey ASC, createdAt ASC, uuid ASC"

/**
 * Appended to every local write: marks the row for the next push and bumps its version, so a
 * push in flight can tell it was overtaken. Sync-side writes ([SyncDao]) never use it.
 */
const val TOUCH = "dirty = 1, version = version + 1"

@Dao
interface ItemDao {

    @Query("SELECT * FROM items WHERE sectionId = :sectionId ORDER BY $ITEM_ORDER")
    fun observeBySection(sectionId: Long): Flow<List<Item>>

    @Query("SELECT * FROM items WHERE sectionId = :sectionId ORDER BY $ITEM_ORDER")
    suspend fun listBySection(sectionId: Long): List<Item>

    @Query(
        "SELECT * FROM items WHERE linkUrl IS NOT NULL AND linkTitle IS NULL " +
            "AND linkFetchFailed = 0",
    )
    suspend fun listLinksMissingPreview(): List<Item>

    /** Synced: a title fetched here fills in links the PWA added, which can't fetch them (CORS). */
    @Query(
        "UPDATE items SET linkTitle = :title, linkImageUrl = :imageUrl, " +
            "linkFetchFailed = 0, $TOUCH WHERE id = :id",
    )
    suspend fun setLinkPreview(id: Long, title: String?, imageUrl: String?)

    @Query("UPDATE items SET linkFetchFailed = 1, $TOUCH WHERE id = :id")
    suspend fun markLinkFetchFailed(id: Long)

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun byId(id: Long): Item?

    /** Device-only (the Claude inbox receipt), so deliberately not a [TOUCH]. */
    @Query("UPDATE items SET pushedAt = :at WHERE id = :id")
    suspend fun markPushed(id: Long, at: Long)

    @Query("UPDATE items SET dueAt = :dueAt, repeatWeeks = :repeatWeeks, $TOUCH WHERE id = :id")
    suspend fun setSchedule(id: Long, dueAt: Long?, repeatWeeks: Int?)

    /**
     * Notes in a remote-backed section that never reached the inbox — the app was offline at
     * add time, or the call failed. Ordered oldest-first so a backlog drains in the order it
     * was captured. No `linkFetchFailed`-style give-up flag exists here on purpose.
     */
    @Query(
        "SELECT i.* FROM items i JOIN sections s ON s.id = i.sectionId " +
            "WHERE s.remoteKind IS NOT NULL AND i.pushedAt IS NULL " +
            "ORDER BY i.createdAt ASC",
    )
    suspend fun listUnpushedInRemoteSections(): List<Item>

    /**
     * Every item counted per section, split by checked state and due date. Feeds the rail
     * badges, the header counts and the clear dialog's totals from one aggregate.
     *
     * Grouping on `dueAt` rather than filtering by it keeps this free of the clock: a `:now`
     * parameter would bind once and the Flow would go on answering for that instant forever.
     * [com.jt.naicenotes.data.util.countsBySection] applies the clock instead. Every
     * unscheduled item still collapses into one bucket per section and checked state, so this
     * stays a real aggregate rather than a row per item.
     */
    @Query("SELECT sectionId, isChecked, dueAt, COUNT(*) AS count FROM items GROUP BY sectionId, isChecked, dueAt")
    fun observeItemBuckets(): Flow<List<SectionItemBucket>>

    @Query("SELECT sortKey FROM items WHERE sectionId = :sectionId ORDER BY $ITEM_ORDER LIMIT 1")
    suspend fun firstKey(sectionId: Long): String?

    /**
     * Moves an item into another section. [uuid] is the item's identity afterwards: the same
     * one for a move within a space, a fresh one when the server would refuse the move (see
     * [com.jt.naicenotes.data.repo.NotesRepository.moveItemToSection]).
     */
    @Query("UPDATE items SET sectionId = :sectionId, sortKey = :sortKey, uuid = :uuid, $TOUCH WHERE id = :id")
    suspend fun setSection(id: Long, sectionId: Long, sortKey: String, uuid: String)

    /** Lands at the top of the target section, the way a freshly added item would. */
    @Transaction
    suspend fun moveToSectionTop(id: Long, targetSectionId: Long, uuid: String) {
        setSection(id, targetSectionId, keyBefore(firstKey(targetSectionId)), uuid)
    }

    @Query("UPDATE items SET sortKey = :sortKey, $TOUCH WHERE id = :id")
    suspend fun setSortKey(id: Long, sortKey: String)

    @Query("SELECT id, sortKey FROM items WHERE id IN (:ids)")
    suspend fun keysFor(ids: List<Long>): List<IdKey>

    /**
     * Applies a new display order, rewriting only the rows that have to move — normally just
     * the one that was dragged. Ids not passed in (the waiting block) keep their keys, which
     * is why a drag in the active block can't reshuffle the one below the divider.
     */
    @Transaction
    suspend fun reorder(newOrder: List<Long>) {
        val keys = keysFor(newOrder).associate { it.id to it.sortKey }
        val ids = newOrder.filter { it in keys }
        rekeyForOrder(ids.map { keys.getValue(it) }).forEachIndexed { i, key ->
            if (key != null) setSortKey(ids[i], key)
        }
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: Item): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(items: List<Item>): List<Long>

    /**
     * Insert at the head of the section. One key before the current first row; nothing else
     * is written, which is what keeps an add to a synced section a single-row push.
     */
    @Transaction
    suspend fun insertAtTop(item: Item): Long =
        insert(item.copy(sortKey = keyBefore(firstKey(item.sectionId))))

    /** Insert a batch at the head, keeping the batch's own order (its first element ends up topmost). */
    @Transaction
    suspend fun insertAllAtTop(sectionId: Long, newItems: List<Item>): List<Long> {
        if (newItems.isEmpty()) return emptyList()
        val first = firstKey(sectionId)?.takeIf(FractionalIndex::isValid)
        val keys = FractionalIndex.nKeysBetween(null, first, newItems.size)
        return insertAll(newItems.mapIndexed { index, item -> item.copy(sortKey = keys[index]) })
    }

    @Query("UPDATE items SET text = :text, $TOUCH WHERE id = :id")
    suspend fun setText(id: Long, text: String)

    @Query("UPDATE items SET isChecked = :checked, $TOUCH WHERE id = :id")
    suspend fun setChecked(id: Long, checked: Boolean)

    @Query("UPDATE items SET isChecked = NOT isChecked, $TOUCH WHERE id = :id")
    suspend fun toggleById(id: Long)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM items WHERE sectionId = :sectionId AND isChecked = 1")
    suspend fun deleteCheckedInSection(sectionId: Long)

    @Query("DELETE FROM items WHERE sectionId = :sectionId")
    suspend fun deleteAllInSection(sectionId: Long)

    @Query("SELECT id FROM items WHERE sectionId = :sectionId")
    suspend fun idsInSection(sectionId: Long): List<Long>

    @Query("UPDATE items SET uuid = :uuid, rev = NULL, $TOUCH WHERE id = :id")
    suspend fun setUuid(id: Long, uuid: String)
}

/** A key that sorts before [first] (or the first key of an empty list). */
fun keyBefore(first: String?): String =
    FractionalIndex.keyBetween(null, first?.takeIf(FractionalIndex::isValid))

/** A key that sorts after [last]. */
fun keyAfter(last: String?): String =
    FractionalIndex.keyBetween(last?.takeIf(FractionalIndex::isValid), null)

data class IdKey(val id: Long, val sortKey: String)

/** One row of [ItemDao.observeItemBuckets]: items in a section sharing a state and due date. */
data class SectionItemBucket(
    val sectionId: Long,
    val isChecked: Boolean,
    val dueAt: Long?,
    val count: Int,
)
