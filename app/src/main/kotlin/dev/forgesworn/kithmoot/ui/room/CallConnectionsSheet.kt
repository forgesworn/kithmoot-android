package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.media.CallConnectionsSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Only this open sheet samples stats; closing cancels polling. Nothing is saved. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CallConnectionsSheet(read: suspend () -> CallConnectionsSnapshot, close: () -> Unit) {
    var snapshot by remember { mutableStateOf<CallConnectionsSnapshot?>(null) }
    var failed by remember { mutableStateOf(false) }
    val currentRead by rememberUpdatedState(read)
    LaunchedEffect(Unit) {
        while (true) {
            try { snapshot = currentRead(); failed = false }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { snapshot = null; failed = true }
            delay(2000)
        }
    }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("Connection details", style = MaterialTheme.typography.titleLarge)
            Text("Room messages and signalling", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            snapshot?.room?.forEach { Text(it) }
            Text("Media from this device", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            if (snapshot == null) Text(if (failed) "Connection details unavailable. Retrying…" else "Reading connections…")
            else if (snapshot!!.people.isEmpty()) Text("No other devices on this call yet.")
            snapshot?.people?.forEach { person ->
                Text(person.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                Text("${person.device} · ${person.connection.state}")
                Text(person.connection.route)
                Text(person.connection.traffic)
                Text(person.connection.video)
            }
            Text("Updates while open. These are this device’s observations; another person may use a different route. Direct connections may run over a VPN. A server name does not verify its operator. STUN helps find addresses; it does not carry call media.", modifier = Modifier.padding(top = 12.dp))
            TextButton(onClick = close) { Text("Close") }
        }
    }
}
