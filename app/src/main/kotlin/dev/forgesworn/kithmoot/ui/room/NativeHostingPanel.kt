package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.forgesworn.kithmoot.epoch.NativeHostingLifecycle
import dev.forgesworn.kithmoot.epoch.NativeHostingState
import dev.forgesworn.kithmoot.epoch.NativeHostingStatus

internal fun nativeHostingLine(hosting: NativeHostingState): String = when (hosting.status) {
    NativeHostingStatus.STARTING -> "Opening room hosting…"
    NativeHostingStatus.READY -> when (hosting.lifecycle) {
        NativeHostingLifecycle.RETIRED -> "Hosting existing members · invitation retired"
        NativeHostingLifecycle.CLOSED -> "Room closed"
        else -> "Hosting · epoch ${hosting.epoch}"
    }
    NativeHostingStatus.RECOVERING -> if (hosting.lifecycle == NativeHostingLifecycle.CLOSED)
        "Room closure pending" else "Hosting update pending"
    NativeHostingStatus.SUSPENDED -> "Hosting paused"
    NativeHostingStatus.FAILED -> "Hosting unavailable · reopen to inspect"
    NativeHostingStatus.CLOSED -> "Room closed"
}

@Composable
internal fun NativeHostingPanel(hosting: NativeHostingState, busy: Boolean = false,
    onChangeKey: ((NativeHostingState) -> Unit)? = null,
    onRemoveMember: ((NativeHostingState, String) -> Unit)? = null) {
    var confirmation by remember(hosting.binding.pin) { mutableStateOf<NativeMemberConfirmation?>(null) }
    val enabled = hosting.canChangeMembers && !busy
    Column {
        Text(nativeHostingLine(hosting), style = MaterialTheme.typography.titleSmall)
        hosting.epoch?.let {
            Text("Last observed epoch $it · ${hosting.approved.size} approved members · ${hosting.removed.size} removed",
                style = MaterialTheme.typography.bodySmall)
        }
        if (hosting.pendingOriginals.isNotEmpty()) Text(
            "${hosting.pendingOriginals.size} saved room updates pending. Hosting state does not confirm delivery to members.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (busy) Text("Saving room update…", style = MaterialTheme.typography.bodySmall)
        if (onChangeKey != null) {
            TextButton(onClick = { confirmation = NativeMemberConfirmation(hosting, null) }, enabled = enabled) {
                Text("Change room key")
            }
        }
        if (onRemoveMember != null) hosting.approved.filter { it != hosting.binding.participant }.forEach { participant ->
            TextButton(onClick = { confirmation = NativeMemberConfirmation(hosting, participant) },
                enabled = enabled, modifier = Modifier.testTag("native-remove-$participant")) {
                Text("Remove ${shortId(participant)}")
            }
        }
    }
    confirmation?.let { request ->
        val current = enabled && request.expected.binding == hosting.binding &&
            request.expected.revision == hosting.revision && request.expected.epoch == hosting.epoch &&
            request.expected.lifecycle == hosting.lifecycle
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(if (request.participant == null) "Change the room key?" else "Remove this member?") },
            text = { Column {
                Text(if (request.participant == null)
                    "Change the key for all approved devices, including devices that are offline. Members may need to reconnect."
                    else "${shortId(request.participant)} will lose access to new messages. Earlier messages may remain on their devices.")
                if (!current && !busy) Text("Room hosting changed. Close this confirmation and try again.")
            } },
            confirmButton = { TextButton(enabled = current, onClick = {
                confirmation = null
                if (request.participant == null) onChangeKey?.invoke(request.expected)
                else onRemoveMember?.invoke(request.expected, request.participant)
            }) { Text(if (request.participant == null) "Change key" else "Remove member") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
        )
    }
}

private data class NativeMemberConfirmation(val expected: NativeHostingState, val participant: String?)
