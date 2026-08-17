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
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.jt.naicenotes.R
import com.jt.naicenotes.data.entity.Item
import com.jt.naicenotes.data.entity.Section
import com.jt.naicenotes.data.remote.UserAgents
import com.jt.naicenotes.ui.common.ColorPickerDialog
import com.jt.naicenotes.ui.common.ConfirmDeleteDialog
import com.jt.naicenotes.ui.common.SectionNameDialog
import com.jt.naicenotes.ui.util.UiPrefs
import com.jt.naicenotes.ui.util.randomSectionColor
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

private sealed interface HomeDialog {
    data object NewSection : HomeDialog
    data object RenameSection : HomeDialog
    data object RecolorSection : HomeDialog
    data object DeleteSection : HomeDialog
    data object ClearChecked : HomeDialog
    data class MoveItem(val item: Item) : HomeDialog
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
    val openCounts by remember(repo) { repo.observeOpenCounts() }
        .collectAsStateWithLifecycle(initialValue = emptyMap())

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
                        openCounts = openCounts,
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
                        openCount = openCounts[selectedSection.id] ?: 0,
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
                        onToggleInbox = {
                            scope.launch {
                                repo.setSectionRemoteKind(
                                    section = selectedSection,
                                    remoteKind = if (selectedSection.isInbox) {
                                        null
                                    } else {
                                        Section.REMOTE_KIND_INBOX
                                    },
                                )
                            }
                        },
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
                        onMoveRequested = { dialog = HomeDialog.MoveItem(it) },
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
                        onScan = { onScan(selectedSection.id) },
                        onSubmit = { text ->
                            scope.launch { repo.addItem(selectedSection.id, text) }
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
    )
}

/**
 * Section title plus the per-section menu the bottom toolbar used to hold. Names no longer fit
 * on the rail tiles, so this line is where the active section is spelled out.
 */
@Composable
private fun ChannelHeader(
    section: Section,
    openCount: Int,
    accent: Color,
    railCollapsed: Boolean,
    onToggleRail: () -> Unit,
    onScan: () -> Unit,
    onClearChecked: () -> Unit,
    onMoveDoneToBottom: () -> Unit,
    onToggleInbox: () -> Unit,
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
                    text = "$openCount open",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (section.isInbox) {
                    Text(
                        text = "· sends to Claude",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    text = {
                        Text(
                            if (section.isInbox) {
                                "Stop sending to Claude"
                            } else {
                                "Send new notes to Claude"
                            },
                        )
                    },
                    onClick = { menuOpen = false; onToggleInbox() },
                )
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
    openCounts: Map<Long, Int>,
    onSelect: (Long) -> Unit,
    onNew: () -> Unit,
    onReorder: (List<Long>) -> Unit,
) {
    val ordered = remember { mutableStateListOf<Section>() }
    LaunchedEffect(sections) {
        val dbIds = sections.map { it.id }
        val localIds = ordered.map { it.id }
        if (dbIds != localIds || sections.size != ordered.size) {
            ordered.clear()
            ordered.addAll(sections)
        } else {
            sections.forEachIndexed { idx, s ->
                if (ordered[idx] != s) ordered[idx] = s
            }
        }
    }

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
        items(ordered, key = { it.id }) { section ->
            ReorderableItem(reorderState, key = section.id) { isDragging ->
                RailTile(
                    section = section,
                    isActive = section.id == selectedId,
                    openCount = openCounts[section.id] ?: 0,
                    dragHandleModifier = Modifier.longPressDraggableHandle(
                        onDragStopped = { onReorder(ordered.map { it.id }) },
                    ),
                    onClick = { onSelect(section.id) },
                )
            }
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
    openCount: Int,
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

                if (openCount > 0) {
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
                                .background(BADGE_COLOR)
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (openCount > 99) "99+" else "$openCount",
                                color = Color.White,
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
    onMoveRequested: (Item) -> Unit,
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

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        orderedItems.add(to.index, orderedItems.removeAt(from.index))
    }

    // The open/done counts moved to the channel header, so the list is now the whole surface.
    LazyColumn(
        state = lazyListState,
        modifier = modifier,
    ) {
        items(orderedItems, key = { it.id }) { item ->
            ReorderableItem(reorderableState, key = item.id) { isDragging ->
                ItemRow(
                    item = item,
                    accent = accent,
                    isDragging = isDragging,
                    canMove = canMove,
                    dragHandleModifier = Modifier.longPressDraggableHandle(
                        onDragStopped = {
                            scope.launch {
                                repo.reorderItems(orderedItems.map { it.id })
                            }
                        },
                    ),
                    onToggle = { scope.launch { repo.toggleItem(item) } },
                    onDelete = { onItemDeleted(item) },
                    onMove = { onMoveRequested(item) },
                    onSaveText = { newText ->
                        scope.launch { repo.updateItemText(item, newText) }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemRow(
    item: Item,
    accent: Color,
    isDragging: Boolean,
    canMove: Boolean,
    dragHandleModifier: Modifier,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onSaveText: (String) -> Unit,
) {
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

    val bg = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh
        else MaterialTheme.colorScheme.surface

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
        color = if (item.isChecked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
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
                    .clickable(onClick = onToggle),
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
                        .clickable {
                            draft = item.text
                            editing = true
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = item.text,
                        style = textStyle,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
            // Delivery receipt. `pushedAt` is only ever set in a section that pushes, so
            // this needs no knowledge of the section — and its absence is what the
            // launch-time retry looks for, making the glyph an honest reflection of state.
            if (item.isPushed && !editing) {
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
            // and unlike the gestures they replace, this one is visible.
            if (!editing) {
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
    onScan: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val focused = text.isNotEmpty()

    fun submit() {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            onSubmit(trimmed)
            text = ""
        }
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
                    text = if (section.hasEmoji) {
                        "Add to ${section.glyph} ${section.name}"
                    } else {
                        "Add to ${section.name}"
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
            if (section.isInbox) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "New notes here go to the Claude inbox",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(14.dp),
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
    onCreateSection: (String, String?) -> Unit,
    onRenameSection: (String, String?) -> Unit,
    onRecolorSection: (Int) -> Unit,
    onDeleteSection: () -> Unit,
    onClearChecked: () -> Unit,
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
        is HomeDialog.MoveItem -> MoveToSectionDialog(
            item = dialog.item,
            sections = sections.filter { it.id != dialog.item.sectionId },
            onDismiss = onDismiss,
            onConfirm = { targetId -> onMoveItem(dialog.item, targetId) },
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

