package com.jt.naicenotes.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViews.RemoteCollectionItems
import androidx.core.content.ContextCompat
import com.jt.naicenotes.MainActivity
import com.jt.naicenotes.NaiceNotesApp
import com.jt.naicenotes.R
import com.jt.naicenotes.data.entity.Item
import com.jt.naicenotes.data.entity.Section
import kotlinx.coroutines.flow.first

/**
 * Builds RemoteViews for the home-screen widget and pushes them via AppWidgetManager.
 *
 * Replaces the Glance-based render path (which never re-invoked `provideGlance` on
 * Samsung One UI, leaving the widget visually stale despite DB updates working).
 */
object ClassicWidgetRenderer {

    /** Up to this many sections can be shown as pills in the widget header. */
    private const val MAX_PILLS = 8

    private val PILL_IDS = intArrayOf(
        R.id.pill_0, R.id.pill_1, R.id.pill_2, R.id.pill_3,
        R.id.pill_4, R.id.pill_5, R.id.pill_6, R.id.pill_7,
    )
    private val PILL_TEXT_IDS = intArrayOf(
        R.id.pill_0_text, R.id.pill_1_text, R.id.pill_2_text, R.id.pill_3_text,
        R.id.pill_4_text, R.id.pill_5_text, R.id.pill_6_text, R.id.pill_7_text,
    )
    private val PILL_DOT_IDS = intArrayOf(
        R.id.pill_0_dot, R.id.pill_1_dot, R.id.pill_2_dot, R.id.pill_3_dot,
        R.id.pill_4_dot, R.id.pill_5_dot, R.id.pill_6_dot, R.id.pill_7_dot,
    )

    /**
     * Re-build the widget for every active widget instance and push fresh RemoteViews.
     * Safe to call from any coroutine context.
     */
    suspend fun renderAll(context: Context) {
        val app = context.applicationContext as? NaiceNotesApp ?: return
        val mgr = AppWidgetManager.getInstance(context)
        val ids = mgr.getAppWidgetIds(
            ComponentName(context, NotesWidgetReceiver::class.java),
        )
        if (ids.isEmpty()) return

        val sections = app.repository.observeSections().first()
        val selectedId = WidgetPrefs.getSelectedId(context)
        val active = sections.firstOrNull { it.id == selectedId } ?: sections.firstOrNull()
        val items = active?.let { app.repository.listItems(it.id) } ?: emptyList()

        ids.forEach { id ->
            val rv = buildRemoteViews(context, sections, active, items, id)
            mgr.updateAppWidget(id, rv)
        }
    }

    private fun buildRemoteViews(
        context: Context,
        sections: List<Section>,
        active: Section?,
        items: List<Item>,
        appWidgetId: Int,
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.widget_main)

        // Day/night-aware colors picked from resources so they auto-switch.
        val inactivePillBg = ContextCompat.getColor(context, R.color.widget_pill_bg_inactive)
        val inactivePillText = ContextCompat.getColor(context, R.color.widget_pill_text_inactive)

        // ----- Pills -----
        val activeId = active?.id
        val pillsToShow = sections.take(MAX_PILLS)
        pillsToShow.forEachIndexed { idx, section ->
            val isActive = section.id == activeId
            val sectionColor = section.color

            rv.setViewVisibility(PILL_IDS[idx], View.VISIBLE)
            rv.setTextViewText(PILL_TEXT_IDS[idx], section.name)

            if (isActive) {
                // Active: filled with section color, white text + white dot
                rv.setColorStateList(
                    PILL_IDS[idx], "setBackgroundTintList",
                    ColorStateList.valueOf(sectionColor),
                )
                rv.setTextColor(PILL_TEXT_IDS[idx], Color.WHITE)
                rv.setColorStateList(
                    PILL_DOT_IDS[idx], "setBackgroundTintList",
                    ColorStateList.valueOf(Color.WHITE),
                )
            } else {
                // Inactive: muted background, day/night-aware text, section-color dot
                rv.setColorStateList(
                    PILL_IDS[idx], "setBackgroundTintList",
                    ColorStateList.valueOf(inactivePillBg),
                )
                rv.setTextColor(PILL_TEXT_IDS[idx], inactivePillText)
                rv.setColorStateList(
                    PILL_DOT_IDS[idx], "setBackgroundTintList",
                    ColorStateList.valueOf(sectionColor),
                )
            }

            // Click → switch active section
            val pillIntent = Intent(context, WidgetActionActivity::class.java).apply {
                data = Uri.parse("naice-widget://section/${section.id}")
                putExtra(WidgetActionActivity.EXTRA_SECTION_ID, section.id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            val pillPi = PendingIntent.getActivity(
                context,
                section.id.toInt(),
                pillIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            rv.setOnClickPendingIntent(PILL_IDS[idx], pillPi)
        }
        // Hide unused pill slots
        for (idx in pillsToShow.size until MAX_PILLS) {
            rv.setViewVisibility(PILL_IDS[idx], View.GONE)
        }

        // ----- App logo (top-left) → opens MainActivity at the active section -----
        rv.setOnClickPendingIntent(
            R.id.app_logo,
            PendingIntent.getActivity(
                context, LOGO_REQUEST_CODE,
                Intent(context, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP,
                    )
                    if (active != null) {
                        putExtra(MainActivity.EXTRA_INITIAL_SECTION_ID, active.id)
                    }
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        // ----- Add button -----
        val accent = active?.color ?: Color.parseColor("#4CAF50")
        rv.setColorStateList(
            R.id.add_button, "setBackgroundTintList",
            ColorStateList.valueOf(accent),
        )
        rv.setColorStateList(
            R.id.add_button, "setImageTintList",
            ColorStateList.valueOf(Color.WHITE),
        )
        rv.setOnClickPendingIntent(
            R.id.add_button,
            PendingIntent.getActivity(
                context, ADD_BUTTON_REQUEST_CODE,
                Intent(context, QuickAddActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )

        // ----- Items list -----
        if (active == null) {
            rv.setViewVisibility(R.id.items_list, View.GONE)
            rv.setViewVisibility(R.id.empty_state, View.VISIBLE)
            rv.setTextViewText(R.id.empty_state, "Open Naice Notes to create a section")
        } else if (items.isEmpty()) {
            rv.setViewVisibility(R.id.items_list, View.GONE)
            rv.setViewVisibility(R.id.empty_state, View.VISIBLE)
            rv.setTextViewText(R.id.empty_state, "No items in ${active.name}")
        } else {
            rv.setViewVisibility(R.id.empty_state, View.GONE)
            rv.setViewVisibility(R.id.items_list, View.VISIBLE)

            // Inline item collection (API 31+) — items are embedded in the
            // widget's RemoteViews directly, no async service/factory. Every
            // updateAppWidget swaps the full list atomically with zero flash.
            val collection = buildItemsCollection(context, items, accent)
            rv.setRemoteAdapter(R.id.items_list, collection)

            // Template PendingIntent (must be mutable so each item's fill-in
            // intent extras are merged at click time).
            val templateIntent = Intent(context, WidgetActionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            val templatePi = PendingIntent.getActivity(
                context,
                TEMPLATE_REQUEST_CODE,
                templateIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            rv.setPendingIntentTemplate(R.id.items_list, templatePi)
        }

        return rv
    }

    private fun buildItemsCollection(
        context: Context,
        items: List<Item>,
        accent: Int,
    ): RemoteCollectionItems {
        val textColor = ContextCompat.getColor(context, R.color.widget_text)
        val mutedColor = ContextCompat.getColor(context, R.color.widget_text_muted)
        val builder = RemoteCollectionItems.Builder()
            .setHasStableIds(true)
            .setViewTypeCount(1)

        items.forEach { item ->
            val itemRv = RemoteViews(context.packageName, R.layout.widget_item)
            itemRv.setImageViewResource(
                R.id.item_checkbox,
                if (item.isChecked) R.drawable.ic_widget_check_on else R.drawable.ic_widget_check_off,
            )
            itemRv.setColorStateList(
                R.id.item_checkbox,
                "setImageTintList",
                ColorStateList.valueOf(if (item.isChecked) accent else mutedColor),
            )
            // item.displayText, not item.text: link rows must show their fetched page
            // title here too, otherwise the widget renders a raw tracking URL.
            val displayText = item.displayText.ifBlank { "(empty)" }
            itemRv.setTextViewText(R.id.item_text, displayText)
            val paintFlags = if (item.isChecked) {
                Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG
            } else {
                Paint.ANTI_ALIAS_FLAG
            }
            itemRv.setInt(R.id.item_text, "setPaintFlags", paintFlags)
            itemRv.setTextColor(
                R.id.item_text,
                if (item.isChecked) mutedColor else textColor,
            )

            val fillIn = Intent().apply {
                data = Uri.parse("naice-widget://item/${item.id}")
                putExtra(WidgetActionActivity.EXTRA_ITEM_ID, item.id)
            }
            itemRv.setOnClickFillInIntent(R.id.item_row, fillIn)

            builder.addItem(item.id, itemRv)
        }
        return builder.build()
    }

    private const val ADD_BUTTON_REQUEST_CODE = 1_000_001
    private const val TEMPLATE_REQUEST_CODE = 1_000_002
    private const val LOGO_REQUEST_CODE = 1_000_003
}
