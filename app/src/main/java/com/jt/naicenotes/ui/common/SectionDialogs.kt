package com.jt.naicenotes.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jt.naicenotes.data.entity.Section
import com.jt.naicenotes.ui.util.SectionColorPalette
import com.jt.naicenotes.ui.util.nextIconInput

/** Everything about a section, in one place. */
data class SectionEdit(
    val name: String,
    val emoji: String?,
    val color: Int,
    val makeClaudeSection: Boolean,
)

/**
 * Create or edit a section: name, icon, colour, and — when editing — whether it's the Claude
 * section. These were three separate dialogs reached from three menu entries; they're all
 * properties of one thing, and splitting them meant three trips to change a section's look.
 *
 * [onMakeClaudeSection] is only offered when editing: a section has to exist before it can be
 * the one notes are sent to.
 */
@Composable
fun SectionDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialEmoji: String?,
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (SectionEdit) -> Unit,
    isClaudeSection: Boolean = false,
    showClaudeToggle: Boolean = false,
) {
    var name by remember { mutableStateOf(initialName) }
    var emoji by remember { mutableStateOf(initialEmoji) }
    var color by remember { mutableIntStateOf(initialColor) }
    var makeClaude by remember { mutableStateOf(isClaudeSection) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // Scrollable: the icon field, its hint and three rows of shortcuts overflow a short
            // screen once the keyboard is up, and AlertDialog won't scroll this slot itself.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Section name") },
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Icon",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        // A plain text field is the whole trick: the system keyboard's emoji tab
                        // is a complete picker with search, skin tones and the user's recents, so
                        // every emoji the device can render is reachable without shipping any.
                        OutlinedTextField(
                            value = emoji.orEmpty(),
                            onValueChange = { emoji = nextIconInput(emoji, it) },
                            singleLine = true,
                            placeholder = { Text("🙂") },
                            textStyle = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.width(96.dp),
                        )
                        GlyphPreview(name = name, emoji = emoji, accent = Color(color))
                    }
                    Text(
                        text = "Tap the field, then your keyboard's emoji key. " +
                            "Leave it empty to use the first letter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Colour",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SectionColorPalette.chunked(COLOR_ROW_SIZE).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { swatch ->
                                ColorSwatch(
                                    color = swatch,
                                    isSelected = swatch.toArgb() == color,
                                    onClick = { color = swatch.toArgb() },
                                )
                            }
                        }
                    }
                }

                if (showClaudeToggle) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Claude section", modifier = Modifier.weight(1f))
                            Switch(
                                checked = makeClaude,
                                // Only ever switched on: the flag moves by designating a
                                // different section, so it can't be switched off into a state
                                // where the composer's checkbox has nowhere to file a note.
                                enabled = !isClaudeSection,
                                onCheckedChange = { makeClaude = it },
                            )
                        }
                        Text(
                            text = if (isClaudeSection) {
                                "Notes sent to Claude are kept here. " +
                                    "Turn it on for another section to move it."
                            } else {
                                "Notes sent with the composer's Claude checkbox are kept here."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onConfirm(SectionEdit(name.trim(), emoji, color, makeClaude))
                    }
                },
                enabled = name.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** How many swatches fit a dialog row without the row scrolling. */
private const val COLOR_ROW_SIZE = 5

/**
 * Live preview of the tile the rail and widget will draw. It builds a throwaway [Section] and reads
 * `glyph` / `hasEmoji` off it rather than re-deriving them, so the preview cannot disagree with the
 * real thing — including the styling split, where an emoji gets a wash of the section colour and a
 * letter gets white-on-solid.
 */
@Composable
private fun GlyphPreview(name: String, emoji: String?, accent: Color) {
    val preview = Section(name = name, color = accent.toArgb(), position = 0, emoji = emoji)

    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (preview.hasEmoji) accent.copy(alpha = 0.22f) else accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = preview.glyph,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
            color = if (preview.hasEmoji) Color.Unspecified else Color.White,
        )
    }
}

@Composable
private fun ColorSwatch(color: Color, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (isSelected) 3.dp else 0.dp,
                color = MaterialTheme.colorScheme.onSurface,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

/**
 * Which notes to remove. The choice *is* the confirmation — each option names its own count, so
 * the destructive one can't be mistaken for the tidy one, and clearing checked items (the common
 * case) doesn't cost two dialogs.
 */
@Composable
fun ClearItemsDialog(
    sectionName: String,
    checkedCount: Int,
    totalCount: Int,
    onDismiss: () -> Unit,
    onClearChecked: () -> Unit,
    onClearAll: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear \"$sectionName\"") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "This can't be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ClearChoice(
                    label = "Checked notes",
                    count = checkedCount,
                    onClick = onClearChecked,
                )
                ClearChoice(
                    label = "All notes",
                    count = totalCount,
                    onClick = onClearAll,
                    destructive = true,
                )
            }
        },
        // The choice is the confirmation, so there's nothing left to confirm.
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ClearChoice(
    label: String,
    count: Int,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val enabled = count > 0
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.outline
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        Text(text = label, color = tint, modifier = Modifier.weight(1f))
        Text(
            text = "$count",
            color = tint,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun ConfirmDeleteDialog(
    title: String,
    message: String,
    confirmLabel: String = "Delete",
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
