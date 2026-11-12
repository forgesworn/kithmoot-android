package dev.forgesworn.kithmoot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.node.NodeKnocks

/**
 * Somebody knocking on one of your node rooms while another screen is showing:
 * their card waits over it, naming the room, until it is answered or the
 * request ends. The room on screen lists its own knocks in its own panel.
 */
@Composable
internal fun AwayKnocks(
    knocks: List<NodeKnocks.Knock>,
    roomName: (String) -> String,
    onLetIn: (NodeKnocks.Knock) -> Unit,
    onDecline: (NodeKnocks.Knock) -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (knocks.isEmpty()) return
    var answered by remember { mutableStateOf(emptySet<String>()) }
    // A request whose answer failed comes back as waiting: its buttons work again.
    LaunchedEffect(knocks) { answered = answered intersect knocks.map { it.request.id }.toSet() }
    Column(modifier.widthIn(max = 480.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState())
        .semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (knock in knocks) key(knock.request.id) {
            val busy = knock.request.id in answered
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${knock.request.name ?: "A guest"} wants to join ${roomName(knock.room)}", style = MaterialTheme.typography.titleSmall)
                    Text(if (knock.opensRoom) "Check who this is. Letting them in opens the room." else "Check who this is before letting them in.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { answered = answered + knock.request.id; onLetIn(knock) }, enabled = !busy) {
                            Text(if (!busy) "Let in" else if (knock.opensRoom) "Opening…" else "Letting in…")
                        }
                        TextButton(onClick = { answered = answered + knock.request.id; onDecline(knock) }, enabled = !busy) { Text("Decline") }
                        TextButton(onClick = { onOpen(knock.room) }) { Text("Open room") }
                    }
                }
            }
        }
    }
}
