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
import androidx.core.graphics.ColorUtils
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

    /**
     * Up to this many sections can be shown as tiles in the widget header. Unlike the name
     * pills these replaced, the number is real: a tile is a fixed 32dp square, so eight of
     * them fit the un-scrollable header where roughly three names did.
     */
    private const val MAX_TILES = 8

    /** Section colour behind an emoji, as an alpha out of 255. Full strength drowns the glyph. */
    private const val EMOJI_TILE_TINT_ALPHA = 56

    private val TILE_IDS = intArrayOf(
        R.id.tile_0, R.id.tile_1, R.id.tile_2, R.id.tile_3,
        R.id.tile_4, R.id.tile_5, R.id.tile_6, R.id.tile_7,
    )
    private val TILE_GLYPH_IDS = intArrayOf(
        R.id.tile_0_glyph, R.id.tile_1_glyph, R.id.tile_2_glyph, R.id.tile_3_glyph,
        R.id.tile_4_glyph, R.id.tile_5_glyph, R.id.tile_6_glyph, R.id.tile_7_glyph,
    )
    private val TILE_INDICATOR_IDS = intArrayOf(
        R.id.tile_0_indicator, R.id.tile_1_indicator, R.id.tile_2_indicator,
        R.id.tile_3_indicator, R.id.tile_4_indicator, R.id.tile_5_indicator,
        R.id.tile_6_indicator, R.id.tile_7_indicator,
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

        // ----- Section tiles -----
        val activeId = active?.id
        val tilesToShow = sections.take(MAX_TILES)
        tilesToShow.forEachIndexed { idx, section ->
            renderTile(context, rv, idx, section, isActive = section.id == activeId)
        }
        // Hide unused tile slots
        for (idx in tilesToShow.size until MAX_TILES) {
            rv.setViewVisibility(TILE_IDS[idx], View.GONE)
        }

        // ----- Section caption -----
        // Names left the tiles, so this line is now the only place the active section is
        // spelled out. Counting off `items` costs nothing — they're already loaded.
        if (active == null) {
            rv.setTextViewText(R.id.section_caption, "")
        } else {
            val open = items.count { !it.isChecked }
            val done = items.size - open
            rv.setTextViewText(
                R.id.section_caption,
                buildString {
                    // The letter fallback is the name's own initial, so pairing the two
                    // reads as "T ToDos". Only a real emoji is worth repeating here.
                    if (active.hasEmoji) append("${active.glyph}  ")
                    append(active.name)
                    append("   ·   $open open")
                    if (done > 0) append(" · $done done")
                },
            )
            rv.setTextColor(
                R.id.section_caption,
                ContextCompat.getColor(context, R.color.widget_text_muted),
            )
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

    /**
     * One section tile: glyph, tint and selected underline, plus the tap target that switches
     * the widget's active section.
     *
     * The emoji and letter cases are deliberately styled differently. An emoji is already a
     * multicoloured glyph, so it sits on a light wash of the section colour; a letter has no
     * colour of its own, so it takes white-on-solid to stay legible. Both read as the same
     * component because the shape and size never change.
     */
    private fun renderTile(
        context: Context,
        rv: RemoteViews,
        idx: Int,
        section: Section,
        isActive: Boolean,
    ) {
        val glyphId = TILE_GLYPH_IDS[idx]

        rv.setViewVisibility(TILE_IDS[idx], View.VISIBLE)
        rv.setTextViewText(glyphId, section.glyph)

        if (section.hasEmoji) {
            rv.setColorStateList(
                glyphId, "setBackgroundTintList",
                ColorStateList.valueOf(
                    ColorUtils.setAlphaComponent(section.color, EMOJI_TILE_TINT_ALPHA),
                ),
            )
            rv.setTextColor(
                glyphId,
                ContextCompat.getColor(context, R.color.widget_text),
            )
        } else {
            rv.setColorStateList(
                glyphId, "setBackgroundTintList",
                ColorStateList.valueOf(section.color),
            )
            rv.setTextColor(glyphId, Color.WHITE)
        }

        // Selected state rides on a separate view: RemoteViews can't add a stroke to a
        // drawable at runtime, and tinting the tile would tint any stroke along with it.
        rv.setViewVisibility(
            TILE_INDICATOR_IDS[idx],
            if (isActive) View.VISIBLE else View.INVISIBLE,
        )
        rv.setColorStateList(
            TILE_INDICATOR_IDS[idx], "setBackgroundTintList",
            ColorStateList.valueOf(section.color),
        )

        val tileIntent = Intent(context, WidgetActionActivity::class.java).apply {
            data = Uri.parse("naice-widget://section/${section.id}")
            putExtra(WidgetActionActivity.EXTRA_SECTION_ID, section.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        rv.setOnClickPendingIntent(
            TILE_IDS[idx],
            PendingIntent.getActivity(
                context,
                section.id.toInt(),
                tileIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
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
