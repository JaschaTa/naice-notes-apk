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
    version = 2,
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

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "naice-notes.db",
                )
                    // Never fall back to destructive migration — this DB is the only
                    // copy of Jascha's real notes and there is no export yet.
                    .addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }
    }
}
