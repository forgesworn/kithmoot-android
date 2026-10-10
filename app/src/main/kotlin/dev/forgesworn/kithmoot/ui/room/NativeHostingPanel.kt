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
    onRemoveMember: ((NativeHostingState, String) -> Unit)? = null,
    onRetireInvitation: ((NativeHostingState) -> Unit)? = null,
    onResendRetirement: ((NativeHostingState, String) -> Unit)? = null,
    onRecoverPending: ((NativeHostingState) -> Unit)? = null) {
    var confirmation by remember(hosting.binding.pin) { mutableStateOf<NativeMemberConfirmation?>(null) }
    var invitationConfirmation by remember(hosting.binding.pin) { mutableStateOf<NativeInvitationConfirmation?>(null) }
    var recoveryConfirmation by remember(hosting.binding.pin) { mutableStateOf<NativeHostingState?>(null) }
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
        if (onRecoverPending != null && hosting.pendingOriginals.isNotEmpty() &&
            hosting.lifecycle != NativeHostingLifecycle.CLOSED) {
            TextButton(onClick = { recoveryConfirmation = hosting }, enabled = hosting.canRetry && !busy) {
                Text("Recover saved update")
            }
        }
        if (hosting.missingRetirementSlots > 0) Text(
            "Earlier invitation notices were not retained. Those notices cannot be resent.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (onRetireInvitation != null && hosting.lifecycle == NativeHostingLifecycle.ACTIVE) {
            TextButton(onClick = { invitationConfirmation = NativeInvitationConfirmation(hosting, null) },
                enabled = hosting.canRetireInvitation && !busy) { Text("Retire invitation") }
        }
        if (onResendRetirement != null) hosting.retirementOriginals.forEachIndexed { index, id ->
            TextButton(onClick = { invitationConfirmation = NativeInvitationConfirmation(hosting, id) },
                enabled = hosting.canResendRetirement && !busy, modifier = Modifier.testTag("native-resend-$id")) {
                Text(if (hosting.retirementOriginals.size == 1) "Resend invitation notice" else "Resend invitation notice ${index + 1}")
            }
        }
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
    recoveryConfirmation?.let { expected ->
        val current = !busy && hosting.canRetry && expected.canRetry && expected.binding == hosting.binding &&
            expected.ownerGeneration == hosting.ownerGeneration && expected.revision == hosting.revision &&
            expected.epoch == hosting.epoch && expected.lifecycle == hosting.lifecycle &&
            expected.pendingOriginals == hosting.pendingOriginals
        AlertDialog(onDismissRequest = { recoveryConfirmation = null },
            title = { Text("Recover this saved update?") },
            text = { Column {
                Text("Continue the saved room update within its remaining retry and airtime limits. It may stay pending if the connection is unavailable. This does not confirm delivery to members.")
                if (!current && !busy) Text("Room hosting changed. Close this confirmation and try again.")
            } },
            confirmButton = { TextButton(enabled = current, onClick = {
                recoveryConfirmation = null
                onRecoverPending?.invoke(expected)
            }) { Text("Recover update") } },
            dismissButton = { TextButton(onClick = { recoveryConfirmation = null }) { Text("Cancel") } },
        )
    }
    invitationConfirmation?.let { request ->
        val retiring = request.original == null
        val current = !busy && request.expected.binding == hosting.binding &&
            request.expected.ownerGeneration == hosting.ownerGeneration &&
            request.expected.revision == hosting.revision && request.expected.epoch == hosting.epoch &&
            request.expected.lifecycle == hosting.lifecycle &&
            if (retiring) hosting.canRetireInvitation else hosting.canResendRetirement && request.original in hosting.retirementOriginals
        AlertDialog(onDismissRequest = { invitationConfirmation = null },
            title = { Text(if (retiring) "Retire this invitation?" else "Resend this invitation notice?") },
            text = { Column {
                Text(if (retiring) "Stop admitting new people with this link or nearby code. Existing approved members can still chat."
                    else "Send the saved notice again within its remaining retry and airtime limits. This does not confirm that members receive it.")
                if (!current && !busy) Text("Room hosting changed. Close this confirmation and try again.")
            } },
            confirmButton = { TextButton(enabled = current, onClick = {
                invitationConfirmation = null
                if (retiring) onRetireInvitation?.invoke(request.expected)
                else onResendRetirement?.invoke(request.expected, requireNotNull(request.original))
            }) { Text(if (retiring) "Retire link" else "Resend notice") } },
            dismissButton = { TextButton(onClick = { invitationConfirmation = null }) { Text("Cancel") } },
        )
    }
    confirmation?.let { request ->
        val current = enabled && request.expected.binding == hosting.binding &&
            request.expected.ownerGeneration == hosting.ownerGeneration &&
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

private data class NativeInvitationConfirmation(val expected: NativeHostingState, val original: String?)
