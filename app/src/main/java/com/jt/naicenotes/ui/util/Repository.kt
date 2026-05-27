package com.jt.naicenotes.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.jt.naicenotes.NaiceNotesApp
import com.jt.naicenotes.data.repo.NotesRepository

@Composable
fun rememberRepository(): NotesRepository {
    val app = LocalContext.current.applicationContext as NaiceNotesApp
    return remember(app) { app.repository }
}
