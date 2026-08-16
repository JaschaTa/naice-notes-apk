package com.jt.naicenotes.ui.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Its own store rather than a key inside `widget_prefs` — DataStore throws if the same file name
 * is created twice in a process, and these two have nothing to do with each other.
 */
private val Context.uiDataStore by preferencesDataStore(name = "ui_prefs")

/** Durable UI state that isn't worth a database row but should survive a restart. */
object UiPrefs {
    private val RAIL_COLLAPSED = booleanPreferencesKey("rail_collapsed")

    fun observeRailCollapsed(context: Context): Flow<Boolean> =
        context.uiDataStore.data.map { it[RAIL_COLLAPSED] == true }

    suspend fun setRailCollapsed(context: Context, collapsed: Boolean) {
        context.uiDataStore.edit { it[RAIL_COLLAPSED] = collapsed }
    }
}
