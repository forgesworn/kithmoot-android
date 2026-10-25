package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import dev.forgesworn.kithmoot.media.SpryClient
import dev.forgesworn.kithmoot.media.SpryPairing
import dev.forgesworn.kithmoot.media.SpryPairingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

@Composable
internal fun SpryDialog(account: String?, syncStatus: String = "", syncAvailable: Boolean = false, onSync: () -> Unit = {}, onUnpairAccount: () -> Unit = {}, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { SpryPairingStore(context) }
    val scope = rememberCoroutineScope()
    var pairing by remember { mutableStateOf<SpryPairing?>(null) }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("Checking Spry…") }
    var confirmAccountRemoval by remember { mutableStateOf(false) }
    LaunchedEffect(account, syncStatus) {
        try {
            pairing = withContext(Dispatchers.IO) { store.read(account) }
            message = pairing?.let { "Paired with ${if (it.endpoint.startsWith("spry:")) "your Spry node" else URI(it.endpoint).host}. Relay access is shared automatically in your calls." }
                ?: "Install Spry, then choose Pair with KithMoot."
        } catch (_: Exception) { message = "The pairing could not be read. Open Spry on your node and pair again." }
        finally { busy = false }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Spry") }, text = { Column {
        Text(message)
        if (account != null) {
            Text(syncStatus.ifBlank { "Pair Spry while signed in to share it, encrypted, with your other Nostr devices." })
            if (syncAvailable) TextButton(onClick = onSync, enabled = !busy) { Text("Sync Spry") }
        } else Text("This pairing stays on this device. Sign in with Nostr before pairing to share it with your other devices.")
    } },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Done") } },
        dismissButton = { pairing?.let { pair -> TextButton(enabled = !busy, onClick = {
            if (account != null && syncAvailable) { confirmAccountRemoval = true; return@TextButton }
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
    if (confirmAccountRemoval) AlertDialog(onDismissRequest = { confirmAccountRemoval = false },
        title = { Text("Unpair Spry on all your devices?") },
        text = { Text("This revokes this pairing for every device that loaded it from your Nostr account. Existing call access can take up to five minutes to expire.") },
        confirmButton = { TextButton(onClick = { confirmAccountRemoval = false; onUnpairAccount(); onDismiss() }) { Text("Unpair Spry") } },
        dismissButton = { TextButton(onClick = { confirmAccountRemoval = false }) { Text("Cancel") } })
}
