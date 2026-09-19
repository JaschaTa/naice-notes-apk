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

/** For the few screens that need more than the repository — currently the untracked Claude send. */
@Composable
fun rememberApp(): NaiceNotesApp = LocalContext.current.applicationContext as NaiceNotesApp
