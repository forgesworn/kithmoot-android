package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.session.AdmissionDecisionPhase
import dev.forgesworn.kithmoot.session.PendingInvitationAdmission

@Composable
internal fun InvitationAdmissionPanel(request: PendingInvitationAdmission, onAnswer: (String, Boolean) -> Unit) {
    val sending = request.phase == AdmissionDecisionPhase.SENDING
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${request.name ?: "A guest"} wants to join", style = MaterialTheme.typography.titleSmall)
            Text("Request device · ${request.device.take(8)}…${request.device.takeLast(8)}",
                style = MaterialTheme.typography.bodySmall)
            val verified = request.verifiedParticipant
            val claimed = request.claimedParticipant
            Text(when {
                verified != null -> "Signed account proof · ${verified.take(8)}…${verified.takeLast(8)}"
                claimed != null -> "Unverified account claim · ${claimed.take(8)}…${claimed.takeLast(8)}"
                else -> "Guest-provided name. No signed account proof."
            }, style = MaterialTheme.typography.bodySmall)
            request.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (sending) Text("Sending the admission grant… Waiting for relay confirmation.",
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onAnswer(request.requestId, false) }, enabled = !sending,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Dismiss") }
                Button(onClick = { onAnswer(request.requestId, true) }, enabled = !sending,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(if (request.phase == AdmissionDecisionPhase.RETRY) "Retry grant" else "Let in")
                }
            }
        }
    }
}
