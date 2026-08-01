package com.jt.naicenotes.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jt.naicenotes.data.entity.Item
import com.jt.naicenotes.data.entity.Section
import com.jt.naicenotes.ui.common.ColorPickerDialog
import com.jt.naicenotes.ui.common.ConfirmDeleteDialog
import com.jt.naicenotes.ui.common.SectionNameDialog
import com.jt.naicenotes.ui.util.SectionColorPalette
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

private sealed interface HomeDialog {
    data object NewSection : HomeDialog
    data object RenameSection : HomeDialog
    data object RecolorSection : HomeDialog
    data object DeleteSection : HomeDialog
    data object ClearChecked : HomeDialog
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (sections.isEmpty()) {
                EmptyState(onCreate = { dialog = HomeDialog.NewSection })
            } else if (selectedSection != null) {
                Header(
                    title = selectedSection.name,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                SectionTabs(
                    sections = sections,
                    selectedId = selectedSection.id,
                    onSelect = { selectedId = it },
                    onNew = { dialog = HomeDialog.NewSection },
                    onReorder = { newOrder ->
                        scope.launch { repo.reorderSections(newOrder) }
                    },
                )
                ItemsList(
                    sectionId = selectedSection.id,
                    accent = accent,
                    repo = repo,
                    scope = scope,
                    modifier = Modifier.weight(1f),
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
                BottomToolbar(
                    onScan = { onScan(selectedSection.id) },
                    onClearChecked = { dialog = HomeDialog.ClearChecked },
                    onMoveDoneToBottom = {
                        scope.launch { repo.moveDoneToBottom(selectedSection.id) }
                    },
                    onRename = { dialog = HomeDialog.RenameSection },
                    onRecolor = { dialog = HomeDialog.RecolorSection },
                    onDelete = { dialog = HomeDialog.DeleteSection },
                )
                HorizontalDivider()
                Composer(
                    sectionName = selectedSection.name,
                    accent = accent,
                    onSubmit = { text ->
                        scope.launch { repo.addItem(selectedSection.id, text) }
                    },
                )
            }
        }
    }

    HomeDialogs(
        dialog = dialog,
        selectedSection = selectedSection,
        onDismiss = { dialog = null },
        onCreateSection = { name ->
            scope.launch {
                val color = SectionColorPalette.random().toArgb()
                val newId = repo.addSection(name, color)
                selectedId = newId
            }
            dialog = null
        },
        onRenameSection = { newName ->
            selectedSection?.let { scope.launch { repo.renameSection(it, newName) } }
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

@Composable
private fun Header(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineLarge.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.5).sp,
        ),
        modifier = modifier,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SectionTabs(
    sections: List<Section>,
    selectedId: Long,
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
        // Only swap real sections — never let drag target the trailing "new" pill slot.
        if (from.index < ordered.size && to.index < ordered.size) {
            ordered.add(to.index, ordered.removeAt(from.index))
        }
    }

    LazyRow(
        state = lazyListState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(ordered, key = { it.id }) { section ->
            ReorderableItem(reorderState, key = section.id) { isDragging ->
                SectionPill(
                    section = section,
                    isActive = section.id == selectedId,
                    isDragging = isDragging,
                    dragHandleModifier = Modifier.longPressDraggableHandle(
                        onDragStopped = {
                            onReorder(ordered.map { it.id })
                        },
                    ),
                    onClick = { onSelect(section.id) },
                )
            }
        }
        item("new") { NewSectionPill(onClick = onNew) }
    }
}

@Composable
private fun SectionPill(
    section: Section,
    isActive: Boolean,
    isDragging: Boolean = false,
    dragHandleModifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = Color(section.color)
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val bg = if (isActive) accent else Color.Transparent
    val fg = if (isActive) Color.White else MaterialTheme.colorScheme.onSurface
    val border = if (isActive) accent else outline
    val dotColor = if (isActive) Color.White else accent

    Row(
        modifier = dragHandleModifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(bg, CircleShape)
            .border(1.5.dp, border, CircleShape)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = section.name,
            color = fg,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun NewSectionPill(onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .border(1.5.dp, outline, CircleShape)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = "new",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun ItemsList(
    sectionId: Long,
    accent: Color,
    repo: com.jt.naicenotes.data.repo.NotesRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    onItemDeleted: (Item) -> Unit,
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

    val openCount = orderedItems.count { !it.isChecked }
    val doneCount = orderedItems.size - openCount

    Column(modifier = modifier) {
        Text(
            text = buildString {
                append("$openCount open")
                if (doneCount > 0) append(" · $doneCount done")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )

        LazyColumn(
            state = lazyListState,
            // weight, not fillMaxSize: the count line above is a sibling, so filling
            // the parent's full height would overflow the column by that much.
            modifier = Modifier.weight(1f),
        ) {
            items(orderedItems, key = { it.id }) { item ->
                ReorderableItem(reorderableState, key = item.id) { isDragging ->
                    ItemRow(
                        item = item,
                        accent = accent,
                        isDragging = isDragging,
                        dragHandleModifier = Modifier.longPressDraggableHandle(
                            onDragStopped = {
                                scope.launch {
                                    repo.reorderItems(orderedItems.map { it.id })
                                }
                            },
                        ),
                        onToggle = { scope.launch { repo.toggleItem(item) } },
                        onDelete = { onItemDeleted(item) },
                        onSaveText = { newText ->
                            scope.launch { repo.updateItemText(item, newText) }
                        },
                    )
                }
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
    dragHandleModifier: Modifier,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
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
    var draft by remember(item.id, item.text) { mutableStateOf(item.text) }
    var hasBeenFocused by remember(item.id, editing) { mutableStateOf(false) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

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
                Icon(
                    imageVector = if (item.isChecked) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (item.isChecked) "Checked" else "Unchecked",
                    tint = if (item.isChecked) accent else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(22.dp),
                )
            }
            if (editing) {
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
        }
    }
}

@Composable
private fun DoneDivider(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DashedLine(modifier = Modifier.weight(1f))
        Text(
            text = "$count done",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DashedLine(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DashedLine(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    Box(
        modifier = modifier
            .height(1.dp)
            .drawWithCache {
                val pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                onDrawWithContent {
                    drawLine(
                        color = color,
                        start = Offset(0f, size.height / 2),
                        end = Offset(size.width, size.height / 2),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = pathEffect,
                    )
                }
            },
    )
}

@Composable
private fun BottomToolbar(
    onScan: () -> Unit,
    onClearChecked: () -> Unit,
    onMoveDoneToBottom: () -> Unit,
    onRename: () -> Unit,
    onRecolor: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = onScan,
            shape = CircleShape,
        ) {
            Icon(
                Icons.Outlined.PhotoCamera,
                contentDescription = "Scan recipe",
            )
        }
        Box {
            FilledTonalIconButton(
                onClick = { open = true },
                shape = CircleShape,
            ) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Section actions")
            }
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Move done to bottom") },
                    onClick = { open = false; onMoveDoneToBottom() },
                )
                DropdownMenuItem(
                    text = { Text("Clear checked") },
                    onClick = { open = false; onClearChecked() },
                )
                DropdownMenuItem(
                    text = { Text("Rename section") },
                    onClick = { open = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text("Change color") },
                    onClick = { open = false; onRecolor() },
                )
                DropdownMenuItem(
                    text = { Text("Delete section") },
                    onClick = { open = false; onDelete() },
                )
            }
        }
    }
}

@Composable
private fun Composer(
    sectionName: String,
    accent: Color,
    onSubmit: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }

    fun submit() {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            onSubmit(trimmed)
            text = ""
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // BasicTextField in a pill rather than Material's TextField: the latter
        // forces a 56dp min height, which eats list rows once the keyboard is up.
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (text.isEmpty()) {
                Text(
                    text = "Add to $sectionName",
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
        IconButton(
            onClick = { submit() },
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = accent,
                contentColor = Color.White,
            ),
            modifier = Modifier.size(40.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Add item")
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
    onDismiss: () -> Unit,
    onCreateSection: (String) -> Unit,
    onRenameSection: (String) -> Unit,
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
        null -> Unit
    }
}

