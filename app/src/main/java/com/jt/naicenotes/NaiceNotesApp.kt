package com.jt.naicenotes

import android.app.Application
import com.jt.naicenotes.data.db.AppDatabase
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

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.get(this)
        repository = NotesRepository(db) {
            ClassicWidgetRenderer.renderAll(applicationContext)
        }
        appScope.launch { seedIfEmpty() }
    }

    private suspend fun seedIfEmpty() {
        if (repository.sectionCount() == 0) {
            repository.addSection(name = "Shopping", color = SEED_COLOR_SHOPPING)
            repository.addSection(name = "Work", color = SEED_COLOR_WORK)
        }
    }

    companion object {
        private const val SEED_COLOR_SHOPPING = 0xFF4CAF50.toInt()
        private const val SEED_COLOR_WORK = 0xFF2196F3.toInt()
    }
}
