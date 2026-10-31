package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.forgesworn.kithmoot.media.SpryClient
import dev.forgesworn.kithmoot.media.SpryPairing
import dev.forgesworn.kithmoot.media.SpryPairingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * Settings, Calls, Your Spry node. Pairing itself happens in Spry, which hands
 * the pairing to this app, so there is no link field here (the web has one).
 * [failedAt] is this run's last refused credential request from the node.
 */
@Composable
internal fun SpryPage(
    account: String?, syncStatus: String, syncAvailable: Boolean, hideIp: Boolean, failedAt: Long?,
    onSync: () -> Unit, onUnpairAccount: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { SpryPairingStore(context) }
    val scope = rememberCoroutineScope()
    var pairing by remember { mutableStateOf<SpryPairing?>(null) }
    var unreadable by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(account, syncStatus) {
        try {
            pairing = withContext(Dispatchers.IO) { store.read(account) }
            unreadable = false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { pairing = null; unreadable = true }
        finally { busy = false }
    }
    fun unpair() {
        val pair = pairing
        // A pairing shared through the account is revoked for every device by the account's own flow.
        if (pair != null && account != null && syncAvailable) { onUnpairAccount(); return }
        busy = true
        scope.launch {
            try {
                if (pair != null) SpryClient.revoke(pair)
                withContext(Dispatchers.IO) { store.remove() }
                pairing = null; unreadable = false
                message = "Spry unpaired. Existing relay access expires within five minutes."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Spry could not be unpaired. Try again when connected, or remove the pairing on your node." }
            finally { busy = false }
        }
    }
    val paired = pairing
    SettingsSection(null) {
        SettingsNote(CallPathWords.SPRY_LEAD)
        when {
            paired != null -> {
                val host = if (paired.endpoint.startsWith("spry:")) null else runCatching { URI(paired.endpoint).host }.getOrNull()
                SettingsStaticRow(host?.let(CallPathWords::spryHost) ?: CallPathWords.SPRY_ON_HEAD, CallPathWords.SPRY_ON, live = true)
                failedAt?.let { SettingsNote(CallPathWords.spryNoAnswerLine(it, System.currentTimeMillis(), java.time.ZoneId.systemDefault(), hideIp), live = true) }
            }
            unreadable -> SettingsStaticRow(CallPathWords.SPRY_BAD_HEAD, CallPathWords.SPRY_BAD, live = true)
            else -> {
                SettingsNote(CallPathWords.SPRY_HOW)
                SettingsNote(CallPathWords.SPRY_EXCL)
            }
        }
        if (account != null) SettingsNote(syncStatus.ifBlank { CallPathWords.SPRY_ACCT_IN }, live = true)
        else SettingsNote(CallPathWords.SPRY_ACCT_OUT)
        if (paired != null && account != null && syncAvailable) SettingsActionRow(CallPathWords.SPRY_SYNC, enabled = !busy, onClick = onSync)
        if (paired != null || unreadable) SettingsActionRow(CallPathWords.SPRY_UNPAIR, destructive = true, enabled = !busy) {
            if (account != null && syncAvailable && paired != null) confirming = true else unpair()
        }
        message?.let { SettingsNote(it, live = true) }
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false },
        title = { Text("Unpair Spry on all your devices?") },
        text = { Text("This revokes this pairing for every device that loaded it from your Nostr account. Existing call access can take up to five minutes to expire.") },
        confirmButton = { TextButton(onClick = { confirming = false; unpair() }) { Text("Unpair Spry", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text(CallPathWords.CANCEL) } })
}
