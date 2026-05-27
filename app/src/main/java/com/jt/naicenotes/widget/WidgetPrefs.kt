package com.jt.naicenotes.widget

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.widgetDataStore by preferencesDataStore(name = "widget_prefs")

object WidgetPrefs {
    private val SELECTED_SECTION_ID = longPreferencesKey("selected_section_id")

    suspend fun getSelectedId(context: Context): Long? =
        context.widgetDataStore.data.first()[SELECTED_SECTION_ID]

    suspend fun setSelectedId(context: Context, id: Long) {
        context.widgetDataStore.edit { it[SELECTED_SECTION_ID] = id }
    }
}
