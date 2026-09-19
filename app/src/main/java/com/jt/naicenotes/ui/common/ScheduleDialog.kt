package com.jt.naicenotes.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jt.naicenotes.data.util.startOfDayIn
import com.jt.naicenotes.data.util.utcDateToLocalStartOfDay
import com.jt.naicenotes.ui.util.formatDueDate

/**
 * A schedule waiting to be attached to the note being composed. Armed means the composer's
 * button lights up and the next submit carries it.
 */
data class PendingSchedule(val dueAt: Long? = null, val repeatWeeks: Int? = null) {
    val isArmed: Boolean get() = dueAt != null || repeatWeeks != null
}

/** Quick picks, in days. Covers what "later" almost always means; the calendar covers the rest. */
private val DUE_PRESETS = listOf(
    "Tomorrow" to 1L,
    "1 week" to 7L,
    "2 weeks" to 14L,
    "1 month" to 30L,
)

private const val MAX_REPEAT_WEEKS = 52

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduleDialog(
    initial: PendingSchedule,
    now: Long,
    onDismiss: () -> Unit,
    onConfirm: (PendingSchedule) -> Unit,
) {
    var dueAt by remember { mutableStateOf(initial.dueAt) }
    var repeatWeeks by remember { mutableStateOf(initial.repeatWeeks) }
    var pickingDate by remember { mutableStateOf(false) }

    if (pickingDate) {
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = dueAt)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { dueAt = utcDateToLocalStartOfDay(it) }
                        pickingDate = false
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) { Text("Cancel") }
            },
        ) { DatePicker(state = pickerState) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Timer") },
        text = {
            Column {
                Text(
                    text = "DUE",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                    color = MaterialTheme.colorScheme.outline,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    DUE_PRESETS.forEach { (label, days) ->
                        val target = startOfDayIn(now, days)
                        FilterChip(
                            selected = dueAt == target,
                            onClick = { dueAt = if (dueAt == target) null else target },
                            label = { Text(label) },
                        )
                    }
                    FilterChip(
                        // Selected only when the date came from the calendar rather than a
                        // preset, so the two never both look chosen.
                        selected = dueAt != null && DUE_PRESETS.none { startOfDayIn(now, it.second) == dueAt },
                        onClick = { pickingDate = true },
                        label = { Text(dueAt?.let { formatDueDate(it) } ?: "Pick a date") },
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                ) {
                    Text("Repeat", modifier = Modifier.weight(1f))
                    Switch(
                        checked = repeatWeeks != null,
                        onCheckedChange = { on -> repeatWeeks = if (on) DEFAULT_REPEAT_WEEKS else null },
                    )
                }

                repeatWeeks?.let { weeks ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        IconButton(
                            onClick = { repeatWeeks = (weeks - 1).coerceAtLeast(1) },
                        ) { Text("−", style = MaterialTheme.typography.titleMedium) }
                        Text(
                            text = if (weeks == 1) "every week" else "every $weeks weeks",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        IconButton(
                            onClick = { repeatWeeks = (weeks + 1).coerceAtMost(MAX_REPEAT_WEEKS) },
                        ) { Text("+", style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Repeat with no date is owed straight away: setting one up is usually
                    // the moment you're about to do the thing, and the cycle starts from the
                    // reset rather than from here.
                    val resolved = when {
                        dueAt != null -> dueAt
                        repeatWeeks != null -> startOfDayIn(now, days = 0)
                        else -> null
                    }
                    onConfirm(PendingSchedule(resolved, repeatWeeks))
                },
            ) { Text("Set") }
        },
        dismissButton = {
            Row {
                if (initial.isArmed) {
                    TextButton(onClick = { onConfirm(PendingSchedule()) }) { Text("Clear") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private const val DEFAULT_REPEAT_WEEKS = 2
