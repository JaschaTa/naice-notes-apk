package com.jt.naicenotes.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jt.naicenotes.data.entity.Item
import com.jt.naicenotes.data.entity.Section

@Database(
    entities = [Section::class, Item::class],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sectionDao(): SectionDao
    abstract fun itemDao(): ItemDao

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
                    )
                    .build().also { instance = it }
            }
    }
}
