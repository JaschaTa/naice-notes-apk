package com.jt.naicenotes.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jt.naicenotes.NaiceNotesApp
import com.jt.naicenotes.data.entity.Section
import com.jt.naicenotes.data.repo.NotesRepository
import com.jt.naicenotes.ui.theme.NaiceNotesTheme
import kotlinx.coroutines.launch

class QuickAddActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as NaiceNotesApp
        setContent {
            NaiceNotesTheme {
                QuickAddDialog(
                    repo = app.repository,
                    initialSectionId = {
                        WidgetPrefs.getSelectedId(this@QuickAddActivity)
                    },
                    onSave = { sectionId, text ->
                        app.appScope.launch { app.repository.addItem(sectionId, text) }
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }
}

@Composable
private fun QuickAddDialog(
    repo: NotesRepository,
    initialSectionId: suspend () -> Long?,
    onSave: (Long, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sections by repo.observeSections().collectAsStateWithLifecycle(initialValue = emptyList())
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    LaunchedEffect(sections) {
        if (selectedId == null && sections.isNotEmpty()) {
            selectedId = initialSectionId() ?: sections.firstOrNull()?.id
        }
    }

    LaunchedEffect(Unit) {
        focus.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an item") },
        text = {
            Column {
                Text(
                    text = "Section",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(sections, key = { it.id }) { section ->
                        Pill(
                            section = section,
                            isActive = section.id == selectedId,
                            onClick = { selectedId = section.id },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("New item…") },
                    singleLine = true,
                    modifier = Modifier
                        .focusRequester(focus)
                        .fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = text.trim()
                    val target = selectedId
                    if (trimmed.isNotEmpty() && target != null) {
                        onSave(target, trimmed)
                    }
                    onDismiss()
                },
                enabled = text.isNotBlank() && selectedId != null,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Pill(
    section: Section,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val accent = Color(section.color)
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val bg = if (isActive) accent else Color.Transparent
    val fg = if (isActive) Color.White else MaterialTheme.colorScheme.onSurface
    val border = if (isActive) accent else outline
    val dotColor = if (isActive) Color.White else accent

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(bg, CircleShape)
            .border(1.5.dp, border, CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
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
