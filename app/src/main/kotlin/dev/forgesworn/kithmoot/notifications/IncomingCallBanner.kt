package dev.forgesworn.kithmoot.notifications

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Shown instead of ringing when a call starts in the room already on
 * screen: the room's foreground state makes a ring or a full-screen
 * intent redundant, but the person still deserves to know somebody
 * started a call, and a way to join it in one tap.
 */
@Composable
fun IncomingCallBanner(callerLabel: String, onAnswer: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.padding(horizontal = 16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("$callerLabel is calling", style = MaterialTheme.typography.bodyLarge)
            }
            TextButton(onClick = onDismiss) { Text("Dismiss") }
            Spacer(Modifier.width(4.dp))
            Button(onClick = onAnswer) { Text("Join") }
        }
    }
}
