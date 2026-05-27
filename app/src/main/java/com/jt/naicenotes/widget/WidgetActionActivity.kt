package com.jt.naicenotes.widget

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.jt.naicenotes.NaiceNotesApp
import kotlinx.coroutines.launch

/**
 * Invisible activity that processes widget tap actions and finishes immediately.
 *
 * - Reads `EXTRA_ITEM_ID` (set by per-item fill-in intent) or `EXTRA_SECTION_ID`
 *   (set by pill click PendingIntent).
 * - Performs the DB / preference update via the application coroutine scope.
 * - Asks ClassicWidgetRenderer to push fresh RemoteViews.
 */
class WidgetActionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate extras=${intent.extras?.keySet()}")

        val itemId = intent.getLongExtra(EXTRA_ITEM_ID, -1L)
        val sectionId = intent.getLongExtra(EXTRA_SECTION_ID, -1L)
        val app = application as NaiceNotesApp

        app.appScope.launch {
            try {
                when {
                    itemId >= 0 -> {
                        Log.d(TAG, "Toggle item $itemId")
                        app.repository.toggleItemById(itemId)
                    }
                    sectionId >= 0 -> {
                        Log.d(TAG, "Select section $sectionId")
                        WidgetPrefs.setSelectedId(applicationContext, sectionId)
                    }
                }
                ClassicWidgetRenderer.renderAll(applicationContext)
            } catch (t: Throwable) {
                Log.e(TAG, "Action failed", t)
            }
        }

        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "WidgetAction"
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_SECTION_ID = "section_id"
    }
}
