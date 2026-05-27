package com.jt.naicenotes.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import com.jt.naicenotes.NaiceNotesApp
import kotlinx.coroutines.launch

class NotesWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val app = context.applicationContext as? NaiceNotesApp ?: return
        app.appScope.launch { ClassicWidgetRenderer.renderAll(context) }
    }
}
