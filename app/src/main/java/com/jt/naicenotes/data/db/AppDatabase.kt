package com.jt.naicenotes.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jt.naicenotes.data.entity.Item
import com.jt.naicenotes.data.entity.PendingDelete
import com.jt.naicenotes.data.entity.Section
import com.jt.naicenotes.data.entity.SyncMeta
import com.jt.naicenotes.data.entity.SyncSpace
import com.jt.naicenotes.data.sync.FractionalIndex
import com.jt.naicenotes.data.sync.LegacyUuid

@Database(
    entities = [Section::class, Item::class, PendingDelete::class, SyncSpace::class, SyncMeta::class],
    version = 8,
    // Exported to app/schemas/ from v8 on, so a future MigrationTestHelper has something to
    // test against. Versions before 8 were never exported.
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sectionDao(): SectionDao
    abstract fun itemDao(): ItemDao
    abstract fun syncDao(): SyncDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** v2 adds link-preview columns. Nullable, so existing rows need no backfill. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN linkUrl TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN linkTitle TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN linkImageUrl TEXT")
            }
        }

        /** v3 records permanently-failed link fetches so they stop being retried. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE items ADD COLUMN linkFetchFailed INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /**
         * v4 clears `linkFetchFailed`. The flag records that a fetch failed under the
         * *then-current* User-Agent policy; v1.4.2 changed that policy (preview-crawler
         * UA with fallback), so previous refusals no longer say anything and the affected
         * links deserve one more attempt. Any change to `UserAgents.ORDERED` should come
         * with a migration like this one.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE items SET linkFetchFailed = 0")
            }
        }

        /**
         * v5 adds the vault task-inbox bridge: `sections.remoteKind` marks a section whose
         * new items get pushed, `items.pushedAt` records that a push landed. Both nullable,
         * so every existing section stays an ordinary one and no row needs backfilling.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sections ADD COLUMN remoteKind TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN pushedAt INTEGER")
            }
        }

        /**
         * v6 adds the optional section emoji. Nullable, so every existing section keeps
         * falling back to its first letter and no row needs backfilling.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sections ADD COLUMN emoji TEXT")
            }
        }

        /**
         * v7 adds the schedule: `dueAt` is when a note becomes active, `repeatWeeks` how
         * often it comes back. Both nullable, so every existing note stays an ordinary one
         * that is always active and no row needs backfilling. Nothing filters on either in
         * SQL — the due/waiting split happens in Kotlin — so neither is indexed.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN dueAt INTEGER")
                db.execSQL("ALTER TABLE items ADD COLUMN repeatWeeks INTEGER")
            }
        }

        /**
         * v8 prepares for sync with Supabase (see supabase/README.md for the contract).
         *
         * - `uuid` on both tables is the sync identity, backfilled with [LegacyUuid] so the first
         *   sync adopts the rows imported into the personal space on 2026-11-11.
         * - `sortKey` replaces `position` for ordering: fractional keys mean adding or dragging
         *   writes one row, not the whole section. Backfilled from the current
         *   `position, createdAt` order, so nothing visibly moves. `position` stays as a dead
         *   column rather than rebuilding both tables.
         * - `dirty`/`version`/`rev` are the push bookkeeping; every existing row starts dirty so
         *   the first sync uploads the phone's current state over the import.
         * - `spaceId` stays null until the first signed-in sync files sections under the
         *   personal space.
         * - Three new tables: the delete outbox, the spaces seen, and sync meta. The highest
         *   ids handed out so far are recorded there: v5 ids at or below them are pre-sync rows,
         *   which the first sync uses to tell "deleted on the phone since the import" apart
         *   from "added on the web".
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("sections", "items")) {
                    db.execSQL("ALTER TABLE $table ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE $table ADD COLUMN sortKey TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE $table ADD COLUMN rev INTEGER")
                    db.execSQL("ALTER TABLE $table ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")
                    db.execSQL("ALTER TABLE $table ADD COLUMN version INTEGER NOT NULL DEFAULT 1")
                }
                db.execSQL("ALTER TABLE sections ADD COLUMN spaceId TEXT")
                db.execSQL("ALTER TABLE items ADD COLUMN createdBy TEXT")

                val sectionIds = longs(db, "SELECT id FROM sections ORDER BY position ASC, createdAt ASC, id ASC")
                FractionalIndex.nKeysBetween(null, null, sectionIds.size).zip(sectionIds) { key, id ->
                    db.execSQL(
                        "UPDATE sections SET uuid = ?, sortKey = ? WHERE id = ?",
                        arrayOf<Any>(LegacyUuid.section(id), key, id),
                    )
                }
                for (sectionId in sectionIds) {
                    val itemIds = longs(
                        db,
                        "SELECT id FROM items WHERE sectionId = $sectionId " +
                            "ORDER BY position ASC, createdAt ASC, id ASC",
                    )
                    FractionalIndex.nKeysBetween(null, null, itemIds.size).zip(itemIds) { key, id ->
                        db.execSQL(
                            "UPDATE items SET uuid = ?, sortKey = ? WHERE id = ?",
                            arrayOf<Any>(LegacyUuid.item(id), key, id),
                        )
                    }
                }

                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sections_uuid` ON `sections` (`uuid`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_items_uuid` ON `items` (`uuid`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_deletes` (`uuid` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, PRIMARY KEY(`uuid`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_spaces` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `createdBy` TEXT NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_meta` (`key` TEXT NOT NULL, " +
                        "`value` TEXT NOT NULL, PRIMARY KEY(`key`))",
                )
                for ((key, table) in listOf(META_LEGACY_MAX_SECTION to "sections", META_LEGACY_MAX_ITEM to "items")) {
                    db.execSQL(
                        "INSERT INTO sync_meta (`key`, `value`) VALUES (?, CAST(COALESCE(" +
                            "(SELECT seq FROM sqlite_sequence WHERE name = '$table'), " +
                            "(SELECT MAX(id) FROM $table), 0) AS TEXT))",
                        arrayOf<Any>(key),
                    )
                }
            }

            private fun longs(db: SupportSQLiteDatabase, sql: String): List<Long> =
                db.query(sql).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        }

        /** Highest section/item id at the v8 migration; see [MIGRATION_7_8]. */
        const val META_LEGACY_MAX_SECTION = "legacy_max_section_id"
        const val META_LEGACY_MAX_ITEM = "legacy_max_item_id"

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "naice-notes.db",
                )
                    // Never fall back to destructive migration — this DB is the only
                    // copy of Jascha's real notes and there is no export yet.
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                    )
                    .build().also { instance = it }
            }
    }
}
