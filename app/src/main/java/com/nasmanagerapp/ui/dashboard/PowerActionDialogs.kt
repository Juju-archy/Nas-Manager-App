package com.nasmanagerapp.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Dialogs for the drawer's Restart/Shut down entries: a confirmation first ([confirmAction]),
 * then a non-dismissable progress dialog while the request is sent ([sendingAction]), then its
 * outcome ([sentAction] or [error]). At most one is shown at a time.
 */
@Composable
fun PowerActionDialogs(
    serverName: String,
    confirmAction: PowerAction?,
    sendingAction: PowerAction?,
    sentAction: PowerAction?,
    error: String?,
    onConfirm: (PowerAction) -> Unit,
    onCancel: () -> Unit,
    onDismissResult: () -> Unit,
) {
    when {
        sendingAction != null -> AlertDialog(
            onDismissRequest = {},
            title = { Text(if (sendingAction == PowerAction.RESTART) "Restarting…" else "Shutting down…") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(16.dp))
                    Text("Sending the request to the server.")
                }
            },
            confirmButton = {},
        )

        sentAction != null -> AlertDialog(
            onDismissRequest = onDismissResult,
            title = { Text(if (sentAction == PowerAction.RESTART) "Restart requested" else "Shutdown requested") },
            text = {
                Text(
                    if (sentAction == PowerAction.RESTART) {
                        "$serverName is restarting. The dashboard will reconnect on its own once it's back up."
                    } else {
                        "$serverName is shutting down."
                    }
                )
            },
            confirmButton = { TextButton(onClick = onDismissResult) { Text("OK") } },
        )

        error != null -> AlertDialog(
            onDismissRequest = onDismissResult,
            title = { Text("Request failed") },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = onDismissResult) { Text("OK") } },
        )

        confirmAction != null -> AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(if (confirmAction == PowerAction.RESTART) "Restart $serverName?" else "Shut down $serverName?") },
            text = {
                Text(
                    if (confirmAction == PowerAction.RESTART) {
                        "The server, its apps, shares and services will be unavailable for a few minutes."
                    } else {
                        "The server will power off. It can't be turned back on from this app."
                    }
                )
            },
            // Both buttons in the confirm slot so Cancel sits far left and the action far right,
            // instead of AlertDialog's default end-aligned pair.
            confirmButton = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    TextButton(
                        onClick = { onConfirm(confirmAction) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(if (confirmAction == PowerAction.RESTART) "Restart" else "Shut down") }
                }
            },
        )
    }
}
