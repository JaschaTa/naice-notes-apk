package com.jt.naicenotes.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.jt.naicenotes.ui.util.SectionEmojiPalette
import com.jt.naicenotes.ui.util.firstGrapheme

@Composable
fun SectionNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, emoji: String?) -> Unit,
    initialEmoji: String? = null,
    /** The section's own colour when renaming; a new section hasn't been assigned one yet. */
    accent: Color? = null,
) {
    var name by remember { mutableStateOf(initialName) }
    var emoji by remember { mutableStateOf(initialEmoji) }

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
                            onValueChange = { emoji = firstGrapheme(it) },
                            singleLine = true,
                            placeholder = { Text("🙂") },
                            textStyle = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.width(96.dp),
                        )
                        GlyphPreview(name = name, emoji = emoji, accent = accent)
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
                        text = "Quick pick",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Shortcuts only — tapping the selected one again clears it.
                    SectionEmojiPalette.chunked(EMOJI_ROW_SIZE).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { candidate ->
                                EmojiChip(
                                    emoji = candidate,
                                    isSelected = candidate == emoji,
                                    onClick = {
                                        emoji = if (emoji == candidate) null else candidate
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), emoji) },
                enabled = name.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** How many icon choices fit a dialog row without the row scrolling. */
private const val EMOJI_ROW_SIZE = 6

/**
 * Live preview of the tile the rail and widget will draw. It builds a throwaway [Section] and reads
 * `glyph` / `hasEmoji` off it rather than re-deriving them, so the preview cannot disagree with the
 * real thing — including the styling split, where an emoji gets a wash of the section colour and a
 * letter gets white-on-solid.
 */
@Composable
private fun GlyphPreview(name: String, emoji: String?, accent: Color?) {
    val tint = accent ?: MaterialTheme.colorScheme.primary
    val preview = Section(name = name, color = tint.toArgb(), position = 0, emoji = emoji)

    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (preview.hasEmoji) tint.copy(alpha = 0.22f) else tint),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = preview.glyph,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
            color = if (preview.hasEmoji) Color.Unspecified else Color.White,
        )
    }
}

/** One quick-pick shortcut. Tapping the selected one again clears the icon. */
@Composable
private fun EmojiChip(
    emoji: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            )
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = emoji, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun ColorPickerDialog(
    title: String,
    selectedColor: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var selected by remember { mutableStateOf(selectedColor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Two rows of swatches
                val palette = SectionColorPalette
                val rowSize = 5
                palette.chunked(rowSize).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { color ->
                            val argb = color.toArgb()
                            val isSelected = argb == selected
                            Column(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (isSelected) 3.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        shape = CircleShape,
                                    )
                                    .clickable { selected = argb },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {}
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let(onConfirm) },
                enabled = selected != null,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
