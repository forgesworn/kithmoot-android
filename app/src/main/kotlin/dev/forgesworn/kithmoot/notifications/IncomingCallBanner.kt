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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
fun IncomingCallBanner(callerLabel: String, onAnswer: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier,
    /** "Voice call" or "Video call" when the ring says; "Call" when it does not. */
    kind: String = "Call") {
    // Answer is the one thing most people want here, so focus starts on it.
    val answer = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { answer.requestFocus() } }
    Card(modifier.padding(horizontal = 16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("$callerLabel is calling", style = MaterialTheme.typography.bodyLarge)
                Text(kind, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Declining here sends nothing: the call carries on for the others.
            TextButton(onClick = onDismiss) { Text("Decline") }
            Spacer(Modifier.width(4.dp))
            Button(onClick = onAnswer, modifier = Modifier.focusRequester(answer)) { Text("Answer") }
        }
    }
}
