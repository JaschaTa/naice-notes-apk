package com.jt.naicenotes.ui.home

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.jt.naicenotes.R
import com.jt.naicenotes.data.entity.Item
import com.jt.naicenotes.data.entity.Section
import com.jt.naicenotes.data.remote.UserAgents
import com.jt.naicenotes.data.util.SectionCounts
import com.jt.naicenotes.data.util.countsBySection
import com.jt.naicenotes.ui.common.ColorPickerDialog
import com.jt.naicenotes.ui.common.ConfirmDeleteDialog
import com.jt.naicenotes.ui.common.PendingSchedule
import com.jt.naicenotes.ui.common.ScheduleDialog
import com.jt.naicenotes.ui.common.SectionNameDialog
import com.jt.naicenotes.ui.util.UiPrefs
import com.jt.naicenotes.ui.util.formatDueDate
import com.jt.naicenotes.ui.util.formatDueRelative
import com.jt.naicenotes.ui.util.formatPushedAt
import com.jt.naicenotes.ui.util.randomSectionColor
import com.jt.naicenotes.ui.util.rememberApp
import com.jt.naicenotes.ui.util.rememberNow
import com.jt.naicenotes.ui.util.reorderWithin
import com.jt.naicenotes.ui.util.rememberRepository
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.abs

/**
 * Fraction of a row's width the finger must actually travel before a swipe deletes.
 * Guards against accidental deletes; raise it to make swiping stiffer still.
 */
private const val SWIPE_DELETE_FRACTION = 0.45f

/**
 * Width of the section rail. Every section stays visible and one tap away, which is what the
 * horizontally-scrolling pill row couldn't promise once there were more than a handful.
 */
private val RAIL_WIDTH = 70.dp

/**
 * The rail is near-black in both themes rather than following the Material You scheme. It's the
 * one surface that has to make section colours read as the accent, and a dynamic mid-tone
 * background fights nine saturated palette colours in a way a neutral dark doesn't.
 */
private val RAIL_BACKGROUND = Color(0xFF1A1D21)

/** Unread-style badge on a rail tile. Fixed, not from the scheme — it must never read as a section colour. */
private val BADGE_COLOR = Color(0xFFE01E5A)

/**
 * "This came due". Fixed for the same reason the badge is, and more so: due-ness has to mean
 * the same thing in every section, and the nine section colours would make it read as
 * "Shopping" instead.
 */
private val DUE_COLOR = Color(0xFFFFB300)

/** A schedule is armed on the composer. One colour for "on", whatever the section's own is. */
private val ARMED_COLOR = Color(0xFF4CAF50)

/** Leading bar on a due row, drawn rather than laid out so it can't shift the content. */
private val DUE_BAR_WIDTH = 3.dp

private sealed interface HomeDialog {
    data object NewSection : HomeDialog
    data object RenameSection : HomeDialog
    data object RecolorSection : HomeDialog
    data object DeleteSection : HomeDialog
    data object ClearChecked : HomeDialog
    data object ClearAllNotes : HomeDialog
    data class MoveItem(val item: Item) : HomeDialog
    data class EditTimer(val item: Item) : HomeDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    initialSectionId: Long? = null,
    onScan: (Long?) -> Unit = {},
) {
    val repo = rememberRepository()
    val scope = rememberCoroutineScope()
    val sections by repo.observeSections().collectAsStateWithLifecycle(initialValue = emptyList())

    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var dialog by remember { mutableStateOf<HomeDialog?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val openBuckets by remember(repo) { repo.observeOpenBuckets() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // One clock for the whole screen: the list partition, the badges and the header all have
    // to agree on what "now" is, or a row can be below the divider while the count says it
    // isn't.
    val now by rememberNow()
    val counts = countsBySection(openBuckets, now)

    // Collapsing the rail hands its 70dp back to the list — worth it while reading or writing
    // long items. Persisted, because it's a preference rather than a transient mode.
    val context = LocalContext.current
    val railCollapsed by remember(context) { UiPrefs.observeRailCollapsed(context) }
        .collectAsStateWithLifecycle(initialValue = false)
    val railWidth by animateDpAsState(
        targetValue = if (railCollapsed) 0.dp else RAIL_WIDTH,
        label = "railWidth",
    )

    // If the app was launched from the widget (logo tap), the widget passes
    // its currently-active section id in. Whenever that changes (cold start or
    // onNewIntent), align the in-app selection to it.
    LaunchedEffect(initialSectionId) {
        if (initialSectionId != null) selectedId = initialSectionId
    }

    LaunchedEffect(sections) {
        // Skip the fallback while sections haven't loaded yet — otherwise we'd
        // override an explicit `initialSectionId` because an empty list trivially
        // contains nothing.
        if (sections.isEmpty()) return@LaunchedEffect
        if (selectedId == null || sections.none { it.id == selectedId }) {
            selectedId = sections.firstOrNull()?.id
        }
    }

    val selectedSection = sections.firstOrNull { it.id == selectedId }
    val accent = selectedSection?.let { Color(it.color) } ?: MaterialTheme.colorScheme.primary
    val claudeSection = sections.firstOrNull { it.isClaudeSection }
    val app = rememberApp()

    Scaffold(
        // The activity is edge-to-edge, so the window never resizes for the keyboard
        // and Compose owns the inset. Union rather than stacking a separate
        // imePadding(): per side this takes the larger of the two, so the bottom is
        // the nav bar when the keyboard is closed and the IME height when it's open —
        // never the sum, which is what squeezed the list to nothing.
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (sections.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                EmptyState(onCreate = { dialog = HomeDialog.NewSection })
            }
        } else if (selectedSection != null) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                // Dropped entirely at zero width rather than kept as a 0dp LazyColumn.
                if (railWidth > 0.dp) {
                    SectionRail(
                        width = railWidth,
                        sections = sections,
                        selectedId = selectedSection.id,
                        counts = counts,
                        onSelect = { selectedId = it },
                        onNew = { dialog = HomeDialog.NewSection },
                        onReorder = { newOrder ->
                            scope.launch { repo.reorderSections(newOrder) }
                        },
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    ChannelHeader(
                        section = selectedSection,
                        counts = counts[selectedSection.id] ?: SectionCounts(open = 0, due = 0),
                        accent = accent,
                        railCollapsed = railCollapsed,
                        onToggleRail = {
                            scope.launch { UiPrefs.setRailCollapsed(context, !railCollapsed) }
                        },
                        onScan = { onScan(selectedSection.id) },
                        onClearChecked = { dialog = HomeDialog.ClearChecked },
                        onMoveDoneToBottom = {
                            scope.launch { repo.moveDoneToBottom(selectedSection.id) }
                        },
                        onMakeClaudeSection = {
                            scope.launch { repo.designateClaudeSection(selectedSection) }
                        },
                        onClearAllNotes = { dialog = HomeDialog.ClearAllNotes },
                        onRename = { dialog = HomeDialog.RenameSection },
                        onRecolor = { dialog = HomeDialog.RecolorSection },
                        onDelete = { dialog = HomeDialog.DeleteSection },
                    )
                    HorizontalDivider()
                    ItemsList(
                        sectionId = selectedSection.id,
                        accent = accent,
                        repo = repo,
                        scope = scope,
                        modifier = Modifier.weight(1f),
                        canMove = sections.size > 1,
                        isClaudeSection = selectedSection.isClaudeSection,
                        now = now,
                        onMoveRequested = { dialog = HomeDialog.MoveItem(it) },
                        onEditTimerRequested = { dialog = HomeDialog.EditTimer(it) },
                        onItemDeleted = { deletedItem ->
                            scope.launch {
                                repo.deleteItem(deletedItem)
                                // Dismiss any previous undo snackbar so the latest
                                // delete always gets the full undo window.
                                snackbarHostState.currentSnackbarData?.dismiss()
                                val label = deletedItem.text.take(30).ifBlank { "Item" }
                                val result = snackbarHostState.showSnackbar(
                                    message = "Deleted \"$label\"",
                                    actionLabel = "Undo",
                                    duration = SnackbarDuration.Short,
                                    withDismissAction = false,
                                )
                                if (result == SnackbarResult.ActionPerformed) {
                                    repo.restoreItem(deletedItem)
                                }
                            }
                        },
                    )
                    Composer(
                        section = selectedSection,
                        accent = accent,
                        claudeSection = claudeSection,
                        now = now,
                        onScan = { onScan(selectedSection.id) },
                        onSubmit = { text, sendToClaude, schedule ->
                            scope.launch {
                                when {
                                    !sendToClaude -> repo.addItem(
                                        sectionId = selectedSection.id,
                                        text = text,
                                        dueAt = schedule.dueAt,
                                        repeatWeeks = schedule.repeatWeeks,
                                    )
                                    claudeSection != null -> repo.addItem(claudeSection.id, text)
                                    // Nowhere to file the receipt, so the note is pushed
                                    // without being stored at all.
                                    else -> app.pushTextDirectly(text)
                                }
                                if (sendToClaude) {
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    snackbarHostState.showSnackbar(
                                        message = if (claudeSection != null) {
                                            "Sent to Claude"
                                        } else {
                                            "Sent to Claude — add a Claude section to track sent notes"
                                        },
                                        duration = SnackbarDuration.Short,
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    HomeDialogs(
        dialog = dialog,
        selectedSection = selectedSection,
        sections = sections,
        onDismiss = { dialog = null },
        onMoveItem = { item, targetId ->
            scope.launch { repo.moveItemToSection(item, targetId) }
            dialog = null
        },
        onSetTimer = { item, schedule ->
            scope.launch { repo.setSchedule(item, schedule.dueAt, schedule.repeatWeeks) }
            dialog = null
        },
        now = now,
        onCreateSection = { name, emoji ->
            scope.launch {
                val color = randomSectionColor()
                val newId = repo.addSection(name, color, emoji)
                selectedId = newId
            }
            dialog = null
        },
        onRenameSection = { newName, emoji ->
            selectedSection?.let { scope.launch { repo.renameSection(it, newName, emoji) } }
            dialog = null
        },
        onRecolorSection = { newColor ->
            selectedSection?.let { scope.launch { repo.recolorSection(it, newColor) } }
            dialog = null
        },
        onDeleteSection = {
            selectedSection?.let { scope.launch { repo.deleteSection(it) } }
            dialog = null
        },
        onClearChecked = {
            selectedSection?.let { scope.launch { repo.clearCheckedItems(it.id) } }
            dialog = null
        },
        onClearAllNotes = {
            selectedSection?.let { scope.launch { repo.clearSection(it.id) } }
            dialog = null
        },
    )
}

/**
 * Section title plus the per-section menu the bottom toolbar used to hold. Names no longer fit
 * on the rail tiles, so this line is where the active section is spelled out.
 */
@Composable
private fun ChannelHeader(
    section: Section,
    counts: SectionCounts,
    accent: Color,
    railCollapsed: Boolean,
    onToggleRail: () -> Unit,
    onScan: () -> Unit,
    onClearChecked: () -> Unit,
    onMoveDoneToBottom: () -> Unit,
    onMakeClaudeSection: () -> Unit,
    onClearAllNotes: () -> Unit,
    onRename: () -> Unit,
    onRecolor: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RailToggle(
            section = section,
            collapsed = railCollapsed,
            accent = accent,
            onClick = onToggleRail,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // No glyph here — the toggle to the left carries it in both states, and a
                // letter fallback next to the name it came from reads as "T ToDos".
                Text(
                    text = section.name,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 3.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
                Text(
                    text = "${counts.open} open",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (counts.due > 0) {
                    Text(
                        text = "· ${counts.due} due",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = DUE_COLOR,
                    )
                }
                if (section.isClaudeSection) {
                    Text(
                        text = "· 🤖",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { contentDescription = "Claude section" },
                    )
                }
            }
        }

        IconButton(onClick = onScan) {
            Icon(
                painter = painterResource(R.drawable.ic_photo_camera),
                contentDescription = "Scan recipe",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Section actions",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Move done to bottom") },
                    onClick = { menuOpen = false; onMoveDoneToBottom() },
                )
                DropdownMenuItem(
                    text = { Text("Clear checked") },
                    onClick = { menuOpen = false; onClearChecked() },
                )
                DropdownMenuItem(
                    text = { Text("Clear all notes") },
                    onClick = { menuOpen = false; onClearAllNotes() },
                )
                // Only offered on sections that aren't it: designating another section moves
                // the flag, so there's never a reason to turn it off and land at none.
                if (!section.isClaudeSection) {
                    DropdownMenuItem(
                        text = { Text("Make this the Claude section") },
                        onClick = { menuOpen = false; onMakeClaudeSection() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Rename & icon") },
                    onClick = { menuOpen = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Change color") },
                    onClick = { menuOpen = false; onRecolor() },
                )
                DropdownMenuItem(
                    text = { Text("Delete section") },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

/**
 * Shows/hides the rail. It's the active section's glyph in both states, never a chevron: the tile
 * always means "this section", and tapping it always toggles the rail. Collapsed, that also keeps
 * the one thing the rail was carrying — which section you're in — visible after it's gone.
 */
@Composable
private fun RailToggle(
    section: Section,
    collapsed: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(13.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (section.hasEmoji) accent.copy(alpha = 0.22f) else accent)
                .semantics {
                    contentDescription = if (collapsed) "Show sections" else "Hide sections"
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = section.glyph,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.ExtraBold,
                ),
                color = if (section.hasEmoji) Color.Unspecified else Color.White,
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SectionRail(
    width: Dp,
    sections: List<Section>,
    selectedId: Long,
    counts: Map<Long, SectionCounts>,
    onSelect: (Long) -> Unit,
    onNew: () -> Unit,
    onReorder: (List<Long>) -> Unit,
) {
    // The Claude section is held out of the reorderable list entirely: it always renders
    // last, below a divider, so its stored position never decides where it sits and a drag
    // can't move it out of place. Everything here degrades to the old behaviour when there
    // isn't one.
    val (claudeSections, normalSections) = sections.partition { it.isClaudeSection }
    val claudeIds = claudeSections.mapTo(mutableSetOf()) { it.id }

    val ordered = remember { mutableStateListOf<Section>() }
    LaunchedEffect(normalSections) {
        val dbIds = normalSections.map { it.id }
        val localIds = ordered.map { it.id }
        if (dbIds != localIds || normalSections.size != ordered.size) {
            ordered.clear()
            ordered.addAll(normalSections)
        } else {
            normalSections.forEachIndexed { idx, s ->
                if (ordered[idx] != s) ordered[idx] = s
            }
        }
    }

    // `ordered` is populated by an effect, so for one frame after a section is designated it
    // still holds the row `claudeSections` already claims — and two `items()` blocks emitting
    // the same key is a hard LazyColumn crash, not a visual glitch. Filtering on the fresh ids
    // keeps the two blocks disjoint in every frame, including that one.
    val railSections = ordered.filter { it.id !in claudeIds }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // Only swap real sections — never let drag target the trailing "new" tile slot.
        if (from.index < ordered.size && to.index < ordered.size) {
            ordered.add(to.index, ordered.removeAt(from.index))
        }
    }

    LazyColumn(
        state = lazyListState,
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(RAIL_BACKGROUND)
            // Tiles keep their full size while the width animates, so without this they
            // spill over the content during the transition.
            .clipToBounds(),
        contentPadding = PaddingValues(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(railSections, key = { it.id }) { section ->
            ReorderableItem(reorderState, key = section.id) { isDragging ->
                RailTile(
                    section = section,
                    isActive = section.id == selectedId,
                    counts = counts[section.id] ?: SectionCounts(open = 0, due = 0),
                    dragHandleModifier = Modifier.longPressDraggableHandle(
                        onDragStopped = { onReorder(railSections.map { it.id }) },
                    ),
                    onClick = { onSelect(section.id) },
                )
            }
        }
        if (claudeSections.isNotEmpty() && railSections.isNotEmpty()) {
            item("claude-divider") {
                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.12f),
                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 14.dp),
                )
            }
        }
        items(claudeSections, key = { it.id }) { section ->
            RailTile(
                section = section,
                isActive = section.id == selectedId,
                counts = counts[section.id] ?: SectionCounts(open = 0, due = 0),
                onClick = { onSelect(section.id) },
            )
        }
        item("new") { NewSectionTile(onClick = onNew) }
    }
}

/**
 * One rail entry: glyph tile, open-count badge, name, and the bar marking the active section.
 *
 * Emoji and letter tiles are styled differently on purpose. An emoji is already a multicoloured
 * glyph, so it sits on a wash of the section colour; a letter has no colour of its own and takes
 * white-on-solid to stay legible. The widget's `renderTile` makes the same distinction, so a
 * section looks the same in both places.
 */
@Composable
private fun RailTile(
    section: Section,
    isActive: Boolean,
    counts: SectionCounts,
    dragHandleModifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = Color(section.color)

    Box(
        modifier = dragHandleModifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Active indicator, flush to the rail's leading edge like Slack's.
        if (isActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(top = 2.dp, bottom = 14.dp)
                    .width(3.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                    .background(Color.White),
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.TopEnd) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(if (isActive) 15.dp else 13.dp))
                        .background(
                            if (section.hasEmoji) accent.copy(alpha = 0.22f) else accent,
                        )
                        .border(
                            width = if (isActive) 1.5.dp else 0.dp,
                            color = if (isActive) Color.White.copy(alpha = 0.9f) else Color.Transparent,
                            shape = RoundedCornerShape(if (isActive) 15.dp else 13.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = section.glyph,
                        style = if (section.hasEmoji) {
                            MaterialTheme.typography.titleLarge
                        } else {
                            MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                            )
                        },
                        color = if (section.hasEmoji) Color.Unspecified else Color.White,
                    )
                }

                if (counts.open > 0) {
                    Box(
                        modifier = Modifier
                            .offset(x = 4.dp, y = (-4).dp)
                            .heightIn(min = 18.dp)
                            .widthIn(min = 18.dp)
                            .clip(CircleShape)
                            .background(RAIL_BACKGROUND)
                            .padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .heightIn(min = 14.dp)
                                .widthIn(min = 14.dp)
                                .clip(CircleShape)
                                // Amber wins over the normal badge colour: something in
                                // here is owed, which is worth seeing from a section you
                                // aren't looking at.
                                .background(if (counts.due > 0) DUE_COLOR else BADGE_COLOR)
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (counts.open > 99) "99+" else "${counts.open}",
                                color = if (counts.due > 0) Color.Black else Color.White,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 9.sp,
                                ),
                            )
                        }
                    }
                }
            }

            Text(
                text = section.name,
                color = if (isActive) Color.White else Color.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 3.dp)
                    .width(RAIL_WIDTH - 8.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun NewSectionTile(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "New section",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun ItemsList(
    sectionId: Long,
    accent: Color,
    repo: com.jt.naicenotes.data.repo.NotesRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    onItemDeleted: (Item) -> Unit,
    canMove: Boolean,
    isClaudeSection: Boolean,
    now: Long,
    onMoveRequested: (Item) -> Unit,
    onEditTimerRequested: (Item) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dbItems by remember(sectionId) { repo.observeItems(sectionId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val orderedItems = remember(sectionId) { mutableStateListOf<Item>() }

    LaunchedEffect(dbItems) {
        val dbIds = dbItems.map { it.id }
        val localIds = orderedItems.map { it.id }
        if (dbIds != localIds || dbItems.size != orderedItems.size) {
            // Structural change (add/remove/reorder from DB): replace wholesale
            orderedItems.clear()
            orderedItems.addAll(dbItems)
        } else {
            // Same IDs in same order — sync per-item changes (e.g. isChecked, text)
            dbItems.forEachIndexed { idx, dbItem ->
                if (orderedItems[idx] != dbItem) orderedItems[idx] = dbItem
            }
        }
    }

    // Two blocks from one list and one clock, so they can't disagree: every row lands in
    // exactly one of them. The rail learned this the hard way — two independently sourced
    // lists can hold the same id for a frame, and a LazyColumn treats that as fatal.
    val (active, waiting) = orderedItems.partition { !it.isWaiting(now) }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // Indices are rendered positions; the list being mutated is in stored order with
        // waiting rows interleaved, so they have to be matched by id.
        reorderWithin(orderedItems, active, from.index, to.index)?.let { (fromIndex, toIndex) ->
            orderedItems.add(toIndex, orderedItems.removeAt(fromIndex))
        }
    }

    // The open/done counts moved to the channel header, so the list is now the whole surface.
    LazyColumn(
        state = lazyListState,
        modifier = modifier,
    ) {
        items(active, key = { it.id }) { item ->
            ReorderableItem(reorderableState, key = item.id) { isDragging ->
                ItemRow(
                    item = item,
                    accent = accent,
                    now = now,
                    isDragging = isDragging,
                    canMove = canMove,
                    isClaudeSection = isClaudeSection,
                    // Sent notes are receipts, so they don't reorder either.
                    dragHandleModifier = if (isClaudeSection) {
                        Modifier
                    } else {
                        Modifier.longPressDraggableHandle(
                            onDragStopped = {
                                scope.launch {
                                    // Only the active ids: `reorderItems` renumbers exactly
                                    // what it's handed, so waiting rows keep their positions
                                    // and a drag can't reshuffle the block below the divider.
                                    repo.reorderItems(
                                        orderedItems.filterNot { it.isWaiting(now) }.map { it.id },
                                    )
                                }
                            },
                        )
                    },
                    onToggle = { scope.launch { repo.toggleItem(item) } },
                    onDelete = { onItemDeleted(item) },
                    onMove = { onMoveRequested(item) },
                    onEditTimer = { onEditTimerRequested(item) },
                    onRemoveDue = { scope.launch { repo.setSchedule(item, null, null) } },
                    onResetTimer = { scope.launch { repo.resetTimer(item, now) } },
                    onMakeActiveNow = {
                        scope.launch { repo.setSchedule(item, now, item.repeatWeeks) }
                    },
                    onSaveText = { newText ->
                        scope.launch { repo.updateItemText(item, newText) }
                    },
                )
            }
        }

        if (waiting.isNotEmpty()) {
            // A String key can't collide with the Long ids around it.
            item("not-due-divider") { NotDueDivider(count = waiting.size) }
        }

        items(waiting, key = { it.id }) { item ->
            ItemRow(
                item = item,
                accent = accent,
                now = now,
                isDragging = false,
                canMove = canMove,
                isClaudeSection = isClaudeSection,
                // Nothing to order down here: the block is sorted by when things come due.
                dragHandleModifier = Modifier,
                onToggle = { scope.launch { repo.toggleItem(item) } },
                onDelete = { onItemDeleted(item) },
                onMove = { onMoveRequested(item) },
                onEditTimer = { onEditTimerRequested(item) },
                onRemoveDue = { scope.launch { repo.setSchedule(item, null, null) } },
                onResetTimer = { scope.launch { repo.resetTimer(item, now) } },
                onMakeActiveNow = {
                    scope.launch { repo.setSchedule(item, now, item.repeatWeeks) }
                },
                onSaveText = { newText ->
                    scope.launch { repo.updateItemText(item, newText) }
                },
            )
        }
    }
}

@Composable
private fun NotDueDivider(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 12.dp, top = 14.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "NOT DUE YET",
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.07.em,
            ),
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemRow(
    item: Item,
    accent: Color,
    now: Long,
    isDragging: Boolean,
    canMove: Boolean,
    isClaudeSection: Boolean,
    dragHandleModifier: Modifier,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onEditTimer: () -> Unit,
    onRemoveDue: () -> Unit,
    onResetTimer: () -> Unit,
    onMakeActiveNow: () -> Unit,
    onSaveText: (String) -> Unit,
) {
    val isDue = item.isDue(now)
    val isWaiting = item.isWaiting(now)
    // Material settles a swipe on fling velocity as well as distance, so a quick
    // flick dismisses however short it was — the accidental-delete case. Material3
    // 1.4 exposes no velocity knob, so gate on how far the finger actually travelled
    // before release and veto anything shorter. positionalThreshold covers the
    // slow-drag path; this covers the fling path.
    var rowWidthPx by remember { mutableFloatStateOf(0f) }
    val stateHolder = remember { arrayOfNulls<SwipeToDismissBoxState>(1) }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.Settled) {
                // Always allow springing back to rest.
                true
            } else if (isClaudeSection) {
                // A receipt of something already sent isn't deletable from here.
                false
            } else {
                val travelled = stateHolder[0]
                    ?.let { state -> runCatching { abs(state.requireOffset()) }.getOrDefault(0f) }
                    ?: 0f
                val farEnough = rowWidthPx > 0f &&
                    travelled >= rowWidthPx * SWIPE_DELETE_FRACTION
                if (farEnough) onDelete()
                farEnough
            }
        },
        positionalThreshold = { totalDistance -> totalDistance * SWIPE_DELETE_FRACTION },
    )
    stateHolder[0] = dismissState

    val bg = when {
        isDragging -> MaterialTheme.colorScheme.surfaceContainerHigh
        // Amber wash rather than the section accent: due has to mean the same thing in
        // every section, and nine saturated accents would make it read as the section.
        isDue -> DUE_COLOR.copy(alpha = 0.07f).compositeOver(MaterialTheme.colorScheme.surface)
        else -> MaterialTheme.colorScheme.surface
    }

    var editing by rememberSaveable(item.id) { mutableStateOf(false) }
    var actionsOpen by remember(item.id) { mutableStateOf(false) }
    var draft by remember(item.id, item.text) { mutableStateOf(item.text) }
    var hasBeenFocused by remember(item.id, editing) { mutableStateOf(false) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val context = LocalContext.current

    fun commit() {
        val trimmed = draft.trim()
        if (trimmed.isNotEmpty() && trimmed != item.text) {
            onSaveText(trimmed)
        } else {
            draft = item.text
        }
        editing = false
    }

    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        textDecoration = if (item.isChecked) TextDecoration.LineThrough else TextDecoration.None,
        fontWeight = if (isDue && !item.isChecked) FontWeight.SemiBold else null,
        color = when {
            item.isChecked -> MaterialTheme.colorScheme.onSurfaceVariant
            // Waiting rows read as present-but-inert; it's the one state where the text
            // itself is the signal, since there's no bar or wash down there.
            isWaiting -> MaterialTheme.colorScheme.outline
            else -> MaterialTheme.colorScheme.onSurface
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier.onSizeChanged { rowWidthPx = it.width.toFloat() },
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = "Delete",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    ) {
        Row(
            modifier = dragHandleModifier
                .fillMaxWidth()
                .background(bg)
                .drawBehind {
                    if (isDue) {
                        drawRect(
                            color = DUE_COLOR,
                            size = Size(DUE_BAR_WIDTH.toPx(), size.height),
                        )
                    }
                }
                .padding(horizontal = 16.dp, vertical = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Compact custom checkbox — smaller than Material Checkbox (which
            // forces a 48dp touch target) so list rows can be tighter.
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .then(if (isClaudeSection) Modifier else Modifier.clickable(onClick = onToggle)),
                contentAlignment = Alignment.Center,
            ) {
                if (item.isChecked) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Checked",
                        tint = accent,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    // Shared with the widget; identical to Material's
                    // radio_button_unchecked, which material-icons-core omits.
                    Icon(
                        painter = painterResource(R.drawable.ic_widget_check_off),
                        contentDescription = "Unchecked",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            if (item.isLink && !editing) {
                LinkContent(
                    item = item,
                    onOpen = { openLink(context, item.linkUrl) },
                    onEdit = {
                        if (isClaudeSection) return@LinkContent
                        draft = item.text
                        editing = true
                    },
                )
            } else if (editing) {
                androidx.compose.foundation.text.BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    textStyle = textStyle,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 6.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { focus ->
                            if (focus.isFocused) {
                                hasBeenFocused = true
                            } else if (hasBeenFocused && editing) {
                                // Only commit on focus loss AFTER the field was
                                // actually focused — otherwise the initial
                                // unfocused-state callback would commit + exit
                                // edit mode before the user ever sees the field.
                                commit()
                            }
                        },
                )
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            } else {
                // Tap target wraps the full row-rest area (not just the text glyphs)
                // so short items like "milk" still have a comfortable hit zone.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 32.dp)
                        .then(
                            if (isClaudeSection) {
                                Modifier
                            } else {
                                Modifier.clickable {
                                    draft = item.text
                                    editing = true
                                }
                            },
                        ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = item.text,
                        style = textStyle,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
            if (item.isScheduled && !editing) {
                ScheduleBadge(
                    item = item,
                    now = now,
                    onRemoveDue = onRemoveDue,
                    onResetTimer = onResetTimer,
                    onMakeActiveNow = onMakeActiveNow,
                )
            }

            // Delivery receipt. In the Claude section it shows whether or not the push has
            // landed, and is the row's only control — tapping it gives the send details,
            // which is the one thing worth knowing about a note that already left. Elsewhere
            // it stays the passive glyph it has always been.
            if (isClaudeSection && !editing) {
                SendReceipt(item = item)
            } else if (item.isPushed && !editing) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Sent to Claude inbox",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(14.dp),
                )
            }

            // Every other gesture on this row is already spoken for — tap the circle toggles,
            // tap the text edits, long-press drags, swipe deletes — so the actions hang off an
            // explicit button rather than the mockup's press-reveal bar. Same four actions,
            // and unlike the gestures they replace, this one is visible. The Claude section
            // has none of them: every action here edits a note, and these already left.
            if (!editing && !isClaudeSection) {
                Box {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .clickable { actionsOpen = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Item actions",
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = actionsOpen,
                        onDismissRequest = { actionsOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (item.isChecked) "Mark as open" else "Mark as done") },
                            onClick = { actionsOpen = false; onToggle() },
                        )
                        DropdownMenuItem(
                            text = { Text("Edit text") },
                            onClick = {
                                actionsOpen = false
                                draft = item.text
                                editing = true
                            },
                        )
                        // The composer can only schedule a note as it's written; this is how
                        // one already on the list gets a timer, changes it, or loses it.
                        DropdownMenuItem(
                            text = { Text(if (item.isScheduled) "Edit timer" else "Add timer") },
                            onClick = { actionsOpen = false; onEditTimer() },
                        )
                        if (item.isLink) {
                            DropdownMenuItem(
                                text = { Text("Open link") },
                                onClick = { actionsOpen = false; openLink(context, item.linkUrl) },
                            )
                        }
                        if (canMove) {
                            DropdownMenuItem(
                                text = { Text("Move to section…") },
                                onClick = { actionsOpen = false; onMove() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = { actionsOpen = false; onDelete() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact link card: thumbnail + title + domain, two lines tall. Tapping opens the page;
 * long-press falls back to editing the raw text, since tap is taken.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.LinkContent(
    item: Item,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
) {
    val dim = item.isChecked
    val strike = if (item.isChecked) TextDecoration.LineThrough else TextDecoration.None

    Row(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 44.dp)
            .combinedClickable(onClick = onOpen, onLongClick = onEdit)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LinkThumbnail(url = item.linkImageUrl, dim = dim)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayText,
                style = MaterialTheme.typography.bodyLarge.copy(textDecoration = strike),
                color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                // One line, per mock A: link rows stay a predictable two lines tall so a
                // few of them can't reflow the list the way variable-height cards would.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.linkDomain?.let { domain ->
                Text(
                    text = domain,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A scheduled note's glyph, and the one action that makes sense for the state it's in.
 *
 * Deliberately one action each rather than a menu: there is no un-recur, because a repeating
 * note that you want to stop repeating is a note you want gone. Delete already lives in the
 * row's ⋮ menu and isn't duplicated here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleBadge(
    item: Item,
    now: Long,
    onRemoveDue: () -> Unit,
    onResetTimer: () -> Unit,
    onMakeActiveNow: () -> Unit,
) {
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    val waiting = item.isWaiting(now)

    val glyph = when {
        waiting -> "⏳"
        item.isRecurring -> "🔁"
        else -> "⏰"
    }

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        state = tooltipState,
        hasAction = true,
        tooltip = {
            RichTooltip(
                title = {
                    Text(item.dueAt?.let { "Due ${formatDueRelative(it, now)}" } ?: "Repeating")
                },
                action = {
                    val (label, act) = when {
                        waiting -> "Make active now" to onMakeActiveNow
                        item.isRecurring -> "Reset timer" to onResetTimer
                        else -> "Remove due" to onRemoveDue
                    }
                    TextButton(
                        onClick = { tooltipState.dismiss(); act() },
                    ) { Text(label) }
                },
            ) {
                Text(
                    buildString {
                        item.dueAt?.let { append(formatDueDate(it)) }
                        item.repeatWeeks?.let {
                            if (isNotEmpty()) append(" · ")
                            append(if (it == 1) "repeats weekly" else "repeats every $it weeks")
                        }
                    },
                )
            }
        },
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .clip(CircleShape)
                .then(
                    if (item.isDue(now)) {
                        Modifier.background(DUE_COLOR.copy(alpha = 0.20f))
                    } else {
                        Modifier
                    },
                )
                .clickable { scope.launch { tooltipState.show() } }
                .padding(5.dp)
                .semantics { contentDescription = "Schedule — tap for details" },
        )
    }
}

/**
 * The Claude section's only control. The arrow says whether the note landed; tapping it says
 * when. A tooltip rather than a dialog — a timestamp doesn't warrant dismissing a modal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendReceipt(item: Item) {
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip {
                Text(
                    item.pushedAt
                        ?.let { "Sent ${formatPushedAt(it)}" }
                        ?: "Not sent yet — will retry",
                )
            }
        },
        state = tooltipState,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = if (item.isPushed) {
                "Sent to Claude — tap for the time"
            } else {
                "Not sent yet"
            },
            tint = if (item.isPushed) {
                MaterialTheme.colorScheme.outline
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable { scope.launch { tooltipState.show() } }
                .padding(8.dp),
        )
    }
}

@Composable
private fun LinkThumbnail(url: String?, dim: Boolean) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        // Drawn underneath, always. A loaded image covers it; a failed or pending load
        // leaves it visible, so a broken image can never render as an empty hole.
        Icon(
            painter = painterResource(R.drawable.ic_link),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(url)
                    // Wikimedia (and others) 403 requests from unrecognised clients.
                    .setHeader("User-Agent", UserAgents.BROWSER)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = if (dim) 0.45f else 1f,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun openLink(context: android.content.Context, url: String?) {
    val target = url ?: return
    val intent = Intent(Intent.ACTION_VIEW, target.toUri()).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show() }
}

/**
 * Boxed composer with its own tool rail — the redesign's one structural claim about adding.
 * The bottom toolbar is gone: scan lives here and in the header, per-section actions moved to
 * the header menu, and per-item actions moved onto the rows.
 *
 * Only actions that exist are shown. The mockup's link and voice buttons had nothing behind
 * them, so they aren't drawn.
 */
@Composable
private fun Composer(
    section: Section,
    accent: Color,
    claudeSection: Section?,
    now: Long,
    onScan: () -> Unit,
    onSubmit: (text: String, sendToClaude: Boolean, schedule: PendingSchedule) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    // Sticky on purpose: capturing a run of tasks for Claude shouldn't mean re-arming the
    // toggle for every one of them.
    var sendToClaude by rememberSaveable { mutableStateOf(false) }
    // Not sticky, unlike the Claude checkbox: a due date belongs to one note, and inheriting
    // it silently is a bug you don't notice until the evidence is below a divider.
    var schedule by remember { mutableStateOf(PendingSchedule()) }
    var schedulingOpen by remember { mutableStateOf(false) }
    val focused = text.isNotEmpty()

    fun submit() {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            onSubmit(trimmed, sendToClaude, schedule)
            text = ""
            schedule = PendingSchedule()
        }
    }

    if (schedulingOpen) {
        ScheduleDialog(
            initial = schedule,
            now = now,
            onDismiss = { schedulingOpen = false },
            onConfirm = { schedule = it; schedulingOpen = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.5.dp,
                color = if (focused) accent else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(14.dp),
            ),
    ) {
        // BasicTextField rather than Material's TextField: the latter forces a 56dp min
        // height, which eats list rows once the keyboard is up.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = 3.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                Text(
                    text = when {
                        sendToClaude -> "Send to Claude"
                        schedule.isArmed -> "Add to ${section.name}, scheduled"
                        section.hasEmoji -> "Add to ${section.glyph} ${section.name}"
                        else -> "Add to ${section.name}"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .clickable(onClick = onScan),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_photo_camera),
                    contentDescription = "Scan recipe",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
            // A note that leaves for Claude never comes back, so there's nothing here for a
            // due date to attach to: whichever of the two is armed puts the other out of reach.
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .then(
                        if (schedule.isArmed) {
                            Modifier.background(ARMED_COLOR.copy(alpha = 0.18f))
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (sendToClaude) Modifier else Modifier.clickable { schedulingOpen = true },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.DateRange,
                    contentDescription = when {
                        sendToClaude -> "Schedule unavailable while sending to Claude"
                        schedule.isArmed -> "Scheduled — tap to change"
                        else -> "Schedule this note"
                    },
                    tint = when {
                        sendToClaude -> MaterialTheme.colorScheme.outlineVariant
                        schedule.isArmed -> ARMED_COLOR
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(19.dp),
                )
            }
            // Available in every section: where a note goes is decided per note, not by a
            // setting on the section you happen to be standing in. Still offered with no
            // Claude section configured — the note is sent either way, it just can't be
            // kept, and the confirmation says so.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .then(
                        if (schedule.isArmed) {
                            Modifier
                        } else {
                            Modifier.clickable { sendToClaude = !sendToClaude }
                        },
                    )
                    .padding(horizontal = 7.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (sendToClaude) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Send to Claude, on",
                        tint = accent,
                        modifier = Modifier.size(17.dp),
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_widget_check_off),
                        contentDescription = if (schedule.isArmed) {
                            "Send to Claude unavailable while scheduled"
                        } else {
                            "Send to Claude, off"
                        },
                        tint = if (schedule.isArmed) {
                            MaterialTheme.colorScheme.outlineVariant
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        modifier = Modifier.size(17.dp),
                    )
                }
                Text(
                    text = "Claude",
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        sendToClaude -> accent
                        schedule.isArmed -> MaterialTheme.colorScheme.outlineVariant
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { submit() },
                enabled = text.isNotBlank(),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = accent,
                    contentColor = Color.White,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledContentColor = MaterialTheme.colorScheme.outline,
                ),
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Add item",
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(onCreate: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("No sections yet", style = MaterialTheme.typography.titleLarge)
            Text(
                "Create a section to start jotting things down.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onCreate) { Text("New section") }
        }
    }
}

@Composable
private fun HomeDialogs(
    dialog: HomeDialog?,
    selectedSection: Section?,
    sections: List<Section>,
    onDismiss: () -> Unit,
    onMoveItem: (Item, Long) -> Unit,
    onSetTimer: (Item, PendingSchedule) -> Unit,
    now: Long,
    onCreateSection: (String, String?) -> Unit,
    onRenameSection: (String, String?) -> Unit,
    onRecolorSection: (Int) -> Unit,
    onDeleteSection: () -> Unit,
    onClearChecked: () -> Unit,
    onClearAllNotes: () -> Unit,
) {
    when (dialog) {
        HomeDialog.NewSection -> SectionNameDialog(
            title = "New section",
            initialName = "",
            confirmLabel = "Create",
            onDismiss = onDismiss,
            onConfirm = onCreateSection,
        )
        HomeDialog.RenameSection -> selectedSection?.let {
            SectionNameDialog(
                title = "Rename section",
                initialName = it.name,
                confirmLabel = "Save",
                onDismiss = onDismiss,
                onConfirm = onRenameSection,
                initialEmoji = it.emoji,
                accent = Color(it.color),
            )
        }
        HomeDialog.RecolorSection -> selectedSection?.let {
            ColorPickerDialog(
                title = "Pick a color",
                selectedColor = it.color,
                onDismiss = onDismiss,
                onConfirm = onRecolorSection,
            )
        }
        HomeDialog.DeleteSection -> selectedSection?.let {
            ConfirmDeleteDialog(
                title = "Delete section?",
                message = "\"${it.name}\" and all its items will be permanently removed.",
                onDismiss = onDismiss,
                onConfirm = onDeleteSection,
            )
        }
        HomeDialog.ClearChecked -> ConfirmDeleteDialog(
            title = "Clear checked items?",
            message = "All checked items in this section will be removed.",
            confirmLabel = "Clear",
            onDismiss = onDismiss,
            onConfirm = onClearChecked,
        )
        HomeDialog.ClearAllNotes -> selectedSection?.let {
            ConfirmDeleteDialog(
                title = "Clear all notes?",
                message = "Every item in \"${it.name}\" will be permanently removed.",
                confirmLabel = "Clear",
                onDismiss = onDismiss,
                onConfirm = onClearAllNotes,
            )
        }
        is HomeDialog.MoveItem -> MoveToSectionDialog(
            item = dialog.item,
            sections = sections.filter { it.id != dialog.item.sectionId },
            onDismiss = onDismiss,
            onConfirm = { targetId -> onMoveItem(dialog.item, targetId) },
        )
        is HomeDialog.EditTimer -> ScheduleDialog(
            initial = PendingSchedule(dialog.item.dueAt, dialog.item.repeatWeeks),
            now = now,
            onDismiss = onDismiss,
            onConfirm = { onSetTimer(dialog.item, it) },
        )
        null -> Unit
    }
}

/**
 * Section picker for moving an item. Lists every section but the one it's already in, using the
 * same glyph tiles as the rail so the target is recognised rather than read.
 */
@Composable
private fun MoveToSectionDialog(
    item: Item,
    sections: List<Section>,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to section") },
        text = {
            Column {
                Text(
                    text = item.displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                sections.forEach { section ->
                    val sectionAccent = Color(section.color)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onConfirm(section.id) }
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(
                                    if (section.hasEmoji) {
                                        sectionAccent.copy(alpha = 0.22f)
                                    } else {
                                        sectionAccent
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = section.glyph,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = if (section.hasEmoji) Color.Unspecified else Color.White,
                            )
                        }
                        Text(
                            text = section.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

