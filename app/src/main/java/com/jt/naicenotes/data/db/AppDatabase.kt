package com.jt.naicenotes.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.jt.naicenotes.data.entity.Item
import com.jt.naicenotes.data.entity.Section

@Database(
    entities = [Section::class, Item::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sectionDao(): SectionDao
    abstract fun itemDao(): ItemDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "naice-notes.db",
                ).build().also { instance = it }
            }
    }
}
