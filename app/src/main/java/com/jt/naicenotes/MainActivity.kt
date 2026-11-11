package com.jt.naicenotes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jt.naicenotes.sync.SyncController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.jt.naicenotes.ui.NotesNavHost
import com.jt.naicenotes.ui.theme.NaiceNotesTheme

class MainActivity : ComponentActivity() {

    private var initialSectionId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readInitialSection(intent)
        // No realtime channel on Android: while the app is on screen, pull on resume and then
        // once a minute, so the partner's edits show up without reopening the app.
        val sync = (application as NaiceNotesApp).sync
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    sync.requestNow()
                    delay(SyncController.FOREGROUND_INTERVAL_MS)
                }
            }
        }
        setContent {
            NaiceNotesTheme {
                NotesNavHost(initialSectionId = initialSectionId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readInitialSection(intent)
    }

    private fun readInitialSection(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_INITIAL_SECTION_ID, -1L)?.takeIf { it >= 0 }
        if (id != null) initialSectionId = id
    }

    companion object {
        const val EXTRA_INITIAL_SECTION_ID = "initial_section_id"
    }
}
