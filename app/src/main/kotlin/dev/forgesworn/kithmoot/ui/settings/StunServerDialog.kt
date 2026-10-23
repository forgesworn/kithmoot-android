package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.media.CallIceServers
import dev.forgesworn.kithmoot.media.CallStunConfig
import dev.forgesworn.kithmoot.media.CallStunStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val STUN_SERVER_TITLE = "STUN server"

/** What the Connections row says: the person's own servers, or who answers when [own] is null because they set none. */
internal fun stunServerSummary(own: List<String>? = null): String =
    if (own == null) CallIceServers.defaultStunWords()
    else "Your own: " + own.joinToString(", ") { it.substringAfter(':').replace(Regex(":[0-9]+$"), "") }

/** The row's words when the saved setting cannot be read; calls stop until it is saved again or removed. */
internal const val STUN_SERVER_UNREADABLE_SUMMARY = "Could not be read. Open it to save it again or use the default."

/** The dialog's explanation. Words for the default come from the build, so a build that names another server says so. */
internal fun stunServerExplanation(defaults: List<String> = CallIceServers.DEFAULT_STUN_URLS): String {
    val fallback = when {
        defaults.isEmpty() -> "Without one, calls only connect on the same network or through your call relay."
        defaults == listOf(CallIceServers.CLOUDFLARE_STUN) -> "Without one, calls ask Cloudflare’s."
        else -> "Without one, calls ask " + defaults.joinToString(", ") + "."
    }
    return "A STUN server tells this phone its public address so a call can connect directly. It sees your IP address " +
        "when a call starts, never the call itself. $fallback With a call relay of your own and nothing here, calls ask " +
        "your relay instead. Set it on each device that needs it; it is never added to room links. Leave and reopen the room after changing it."
}

@Composable
internal fun StunServerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { CallStunStore(context) }
    val scope = rememberCoroutineScope()
    var urls by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store) {
        try {
            urls = withContext(Dispatchers.IO) { store.read() }?.urls?.joinToString(", ") ?: ""
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "The saved STUN server could not be read. Save it again or remove it." }
        finally { loading = false }
    }
    fun save(remove: Boolean) {
        val value = try { if (remove) null else CallStunConfig.parse(urls) }
        catch (error: IllegalArgumentException) { message = error.message; return }
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(value) }
                onDismiss()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "The STUN server could not be saved. Try again." }
            finally { saving = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(STUN_SERVER_TITLE) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stunServerExplanation())
            OutlinedTextField(urls, { urls = it; message = null }, Modifier.fillMaxWidth(),
                label = { Text("Your STUN server") }, placeholder = { Text("stun:stun.example:3478") },
                supportingText = { Text("Separate addresses with commas. Leave empty for the default.") },
                enabled = !loading && !saving, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton({ save(true) }, enabled = !loading && !saving) { Text("Use the default") }
        }
    }, confirmButton = {
        TextButton({ save(false) }, enabled = !loading && !saving) { Text("Save") }
    }, dismissButton = { TextButton(onDismiss, enabled = !saving) { Text("Cancel") } })
}
