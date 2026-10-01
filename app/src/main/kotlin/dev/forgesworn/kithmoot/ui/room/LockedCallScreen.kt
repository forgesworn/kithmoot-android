package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.ui.RoomState

/**
 * A call answered on the lock screen, while the phone is still locked: who
 * is on it, the microphone and the way off it, and nothing else. The room's
 * chat, work, people and other rooms stay behind the lock screen, the way a
 * phone call shows the call and not the phone - whoever is holding the
 * phone sees only what the call itself already told them. Unlocking opens
 * the whole room as usual.
 */
@Composable
fun LockedCallScreen(call: RoomState, micAllowed: Boolean, onToggleMic: () -> Unit, onLeave: () -> Unit, onUnlock: () -> Unit) {
    val heard = call.micOn && !call.micMuted
    val others = call.tiles.filter { !it.isSelf }
    val live = call.mediaConnections.values.any { it == "connected" || it == "completed" }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.heightIn(min = 48.dp))
            Text(
                when {
                    !call.onCall -> "Joining the call…"
                    others.isEmpty() -> "Waiting for the others"
                    else -> others.joinToString(", ") { lockedCallName(call, it) }
                },
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                "${if (live) "On a call" else "Connecting"} in ${call.name.ifBlank { "KithMoot" }}".takeIf { call.onCall } ?: "",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            // Without the permission, only an unlocked phone can ask for it.
            if (!micAllowed) FilledTonalButton(
                onClick = onUnlock,
                enabled = call.onCall,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Unlock to use your microphone") }
            else FilledTonalButton(
                onClick = onToggleMic,
                enabled = call.onCall,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
                    contentDescription = "Microphone"
                    stateDescription = if (heard) "On" else "Off"
                },
            ) { Text(if (heard) "Mic on" else "Mic off") }
            Button(
                onClick = onLeave,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Leave call") }
            TextButton(onClick = onUnlock, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Unlock for chat and video")
            }
        }
    }
}

/** The same name the call view gives a person: their contact card, profile, room name, then short key. */
private fun lockedCallName(call: RoomState, tile: ParticipantTile): String =
    tile.cardName?.takeIf { it.isNotBlank() } ?: call.profiles[tile.participant]?.name ?: tile.name ?: shortNpub(tile.participant)
