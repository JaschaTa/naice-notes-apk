package com.jt.naicenotes.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.jt.naicenotes.data.entity.Section
import com.jt.naicenotes.data.sync.SpacePerson
import com.jt.naicenotes.data.sync.sharingError
import com.jt.naicenotes.sync.SyncController
import kotlinx.coroutines.launch

/** Your relation to a section: your own private one, one you shared, or one shared with you. */
enum class SectionRole { PRIVATE, OWNER, MEMBER }

enum class ShareStep { MAIN, UNSHARE, LEAVE }

private const val WEB_HOST = "naice-notes.com"

/**
 * Share a private section; for a shared one, who's in it plus "Stop sharing" (owner) or
 * "Leave section" (member). A port of the PWA's ShareSheet, same wording and same rules. These
 * are server calls, so unlike everything else they need a connection — errors show here rather
 * than being queued.
 */
@Composable
fun ShareDialog(
    section: Section,
    role: SectionRole,
    myEmail: String,
    sync: SyncController,
    initialStep: ShareStep = ShareStep.MAIN,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(initialStep) }
    var people by remember { mutableStateOf<List<SpacePerson>?>(null) }
    var peopleFailed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(section.spaceId, role, reload) {
        if (role == SectionRole.PRIVATE) return@LaunchedEffect
        runCatching { sync.people().filter { it.spaceId == section.spaceId } }
            .onSuccess { people = it; peopleFailed = false }
            .onFailure { peopleFailed = true }
    }

    val me = myEmail.lowercase()
    val ownerName = people?.firstOrNull { it.role == "owner" }?.email ?: "The owner"
    val others = people.orEmpty().filter { it.email != me }

    fun run(message: String, close: Boolean = true, action: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                action()
                onDone(message)
                if (close) onDismiss()
            } catch (e: Exception) {
                error = sharingError(e)
            } finally {
                busy = false
            }
        }
    }

    fun share() {
        val to = email.trim().lowercase()
        if (to.isEmpty()) return
        run("Shared \"${section.name}\" with $to", close = role == SectionRole.PRIVATE) {
            sync.share(section, to)
            email = ""
            reload++
        }
    }

    val back = { if (initialStep == ShareStep.MAIN) step = ShareStep.MAIN else onDismiss() }

    @Composable
    fun EmailField(label: String) = OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text(label) },
        placeholder = { Text("name@example.com") },
        singleLine = true,
        enabled = !busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )

    @Composable
    fun ErrorLine() = error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    when {
        step == ShareStep.UNSHARE -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Stop sharing?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val names = others.joinToString(", ") { it.email }.ifEmpty { "everyone else" }
                    Text("\"${section.name}\" stays with you, notes and all, and disappears for $names.")
                    ErrorLine()
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = { run("\"${section.name}\" is private again") { sync.unshare(section) } }) {
                    Text(if (busy) "Stopping…" else "Stop sharing", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = back) { Text("Cancel") } },
        )

        step == ShareStep.LEAVE -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Leave section?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("\"${section.name}\" disappears from your sections. $ownerName keeps it and can share it with you again.")
                    ErrorLine()
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = { run("Left \"${section.name}\"") { sync.leave(section) } }) {
                    Text(if (busy) "Leaving…" else "Leave", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = back) { Text("Cancel") } },
        )

        role == SectionRole.PRIVATE -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Share \"${section.name}\"") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Shared notes are better notes 🥰  " +
                            "You can share sections with your friends, so you can check notes together.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    EmailField("Their email")
                    Text(
                        "Your friends don't have Naice Notes yet? Add them here and we'll send them the invite.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ErrorLine()
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && email.isNotBlank(), onClick = ::share) { Text(if (busy) "Sharing…" else "Share") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )

        else -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("\"${section.name}\"") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (role == SectionRole.OWNER) {
                            "Shared by you. Everyone here can add, edit and tick off notes; only you can delete it."
                        } else {
                            "Shared with you by $ownerName. You can add, edit and tick off notes; only they can delete it."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("People", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    if (peopleFailed) {
                        Text("Can't load who's in it right now.", style = MaterialTheme.typography.bodySmall)
                    }
                    people.orEmpty().forEach { p ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                if (p.email == me) "${p.email} (you)" else p.email,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                when {
                                    p.role == "owner" -> "Owner"
                                    p.joined -> "Joined"
                                    else -> "Invited"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (others.any { !it.joined }) {
                        Text(
                            "Invited people open $WEB_HOST and sign in with that email.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (role == SectionRole.OWNER) {
                        EmailField("Add someone")
                        TextButton(enabled = !busy && email.isNotBlank(), onClick = ::share) { Text(if (busy) "Adding…" else "Add") }
                    }
                    ErrorLine()
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = { step = if (role == SectionRole.OWNER) ShareStep.UNSHARE else ShareStep.LEAVE },
                ) {
                    Text(
                        if (role == SectionRole.OWNER) "Stop sharing" else "Leave section",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
        )
    }
}
