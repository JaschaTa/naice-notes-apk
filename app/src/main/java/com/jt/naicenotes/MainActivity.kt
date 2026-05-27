package com.jt.naicenotes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jt.naicenotes.ui.NotesNavHost
import com.jt.naicenotes.ui.theme.NaiceNotesTheme

class MainActivity : ComponentActivity() {

    private var initialSectionId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readInitialSection(intent)
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
