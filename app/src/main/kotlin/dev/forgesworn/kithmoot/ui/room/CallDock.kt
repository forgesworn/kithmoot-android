package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.ui.RoomState

/**
 * The call, docked above another room. Nothing a person navigates to ends a
 * call: this is the way back to it and the way off it. The web client's dock
 * says the same things; see app/src/call-dock.ts there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CallDock(call: RoomState, onToggleMic: () -> Unit, onBack: () -> Unit, onLeave: () -> Unit) {
    val others = call.tiles.count { !it.isSelf }
    val room = call.name.ifBlank { "your room" }
    val summary = when {
        !call.onCall -> "Still in $room."
        others == 0 -> "On a call in $room. Just you."
        others == 1 -> "On a call in $room with one other."
        else -> "On a call in $room with $others others."
    }
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(summary, style = MaterialTheme.typography.titleSmall)
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (call.onCall) FilledTonalButton(
                    onClick = onToggleMic,
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        contentDescription = "Microphone"
                        stateDescription = if (call.micOn) "On" else "Off"
                    },
                ) { Text(if (call.micOn) "Mic on" else "Mic off") }
                Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to the call") }
                if (call.onCall) OutlinedButton(
                    onClick = onLeave,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Leave call") }
            }
        }
    }
}
