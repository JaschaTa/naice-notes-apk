package com.jt.naicenotes.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jt.naicenotes.data.sync.signInError
import com.jt.naicenotes.sync.SyncController
import com.jt.naicenotes.sync.SyncStatus
import com.jt.naicenotes.ui.util.formatPushedAt
import kotlinx.coroutines.launch

/**
 * Sign in to sync, see whether it's working, sign out. Signing in for the first time *is*
 * creating the account (invite-only, enforced server-side); there's no password, only a
 * 6-digit code by email — the same flow as the PWA.
 */
@Composable
fun AccountDialog(sync: SyncController, onDismiss: () -> Unit) {
    val status by sync.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun attempt(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                error = signInError(e)
            } finally {
                busy = false
            }
        }
    }

    val signedIn = status is SyncStatus.Idle || status is SyncStatus.Syncing || status is SyncStatus.Failed

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (signedIn) "Sync" else "Sign in to sync") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val s = status) {
                    SyncStatus.Unconfigured -> Text("Sync isn't configured in this build.")
                    SyncStatus.SignedOut -> {
                        Text(
                            "Your notes are kept on naice-notes.com as well, so they show up in the " +
                                "web app and in sections you share. The Claude section stays on this phone.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it; codeSent = false },
                            label = { Text("Email") },
                            singleLine = true,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        )
                        if (codeSent) {
                            OutlinedTextField(
                                value = code,
                                onValueChange = { code = it.filter(Char::isDigit).take(CODE_LENGTH) },
                                label = { Text("6-digit code from the email") },
                                singleLine = true,
                                enabled = !busy,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            )
                        }
                    }
                    is SyncStatus.Idle -> {
                        Text("Signed in as ${s.email}")
                        Text(
                            s.lastSyncedAt?.let { "Last synced ${formatPushedAt(it)}" } ?: "Not synced yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (s.refused > 0) {
                            Text(
                                "${s.refused} change(s) were refused by the server and kept only on this phone.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    is SyncStatus.Syncing -> {
                        Text("Signed in as ${s.email}")
                        Text("Syncing…", style = MaterialTheme.typography.bodySmall)
                    }
                    is SyncStatus.Failed -> {
                        Text("Signed in as ${s.email}")
                        Text(
                            "Last attempt failed: ${s.message}. Changes are kept and sent on the next try.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when {
                status == SyncStatus.SignedOut && !codeSent -> TextButton(
                    enabled = !busy && email.contains('@'),
                    onClick = { attempt { sync.requestCode(email); codeSent = true } },
                ) { Text("Send code") }
                status == SyncStatus.SignedOut -> TextButton(
                    enabled = !busy && code.length == CODE_LENGTH,
                    onClick = { attempt { sync.verifyCode(email, code) } },
                ) { Text("Sign in") }
                signedIn -> TextButton(
                    enabled = status !is SyncStatus.Syncing,
                    onClick = { sync.requestNow() },
                ) { Text("Sync now") }
                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (signedIn) {
                TextButton(onClick = { attempt { sync.signOut() } }) { Text("Sign out") }
            } else if (codeSent) {
                TextButton(enabled = !busy, onClick = { attempt { sync.requestCode(email) } }) { Text("Resend") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private const val CODE_LENGTH = 6
