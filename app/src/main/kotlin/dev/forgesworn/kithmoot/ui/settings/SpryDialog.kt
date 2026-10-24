package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.forgesworn.kithmoot.media.SpryClient
import dev.forgesworn.kithmoot.media.SpryPairing
import dev.forgesworn.kithmoot.media.SpryPairingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

@Composable
internal fun SpryDialog(account: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { SpryPairingStore(context) }
    val scope = rememberCoroutineScope()
    var pairing by remember { mutableStateOf<SpryPairing?>(null) }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("Checking Spry…") }
    LaunchedEffect(account) {
        try {
            pairing = withContext(Dispatchers.IO) { store.read(account) }
            message = pairing?.let { "Paired with ${if (it.endpoint.startsWith("spry:")) "your Spry node" else URI(it.endpoint).host}. Relay access is shared automatically in your calls." }
                ?: "Install Spry, then choose Pair with KithMoot."
        } catch (_: Exception) { message = "The pairing could not be read. Open Spry on your node and pair again." }
        finally { busy = false }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Spry") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Done") } },
        dismissButton = { pairing?.let { pair -> TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    SpryClient.revoke(pair)
                    withContext(Dispatchers.IO) { store.remove() }
                    pairing = null
                    message = "Spry unpaired. Existing relay access expires within five minutes."
                } catch (_: Exception) { message = "Spry could not be unpaired. Try again when connected, or remove the pairing on your node." }
                finally { busy = false }
            }
        }) { Text("Unpair Spry") } } },
    )
}
