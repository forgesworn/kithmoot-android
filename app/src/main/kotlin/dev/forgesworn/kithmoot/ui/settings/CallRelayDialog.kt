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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.media.CallRelayConfig
import dev.forgesworn.kithmoot.media.CallRelayStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CallRelayDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { CallRelayStore(context) }
    val scope = rememberCoroutineScope()
    // Credentials do not enter saved instance state.
    var urls by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store) {
        try {
            val value = withContext(Dispatchers.IO) { store.read() }
            urls = value?.urls?.joinToString(", ") ?: ""
            username = value?.username ?: ""
            password = value?.credential ?: ""
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "The saved call relay could not be read. Save it again or remove it." }
        finally { loading = false }
    }
    fun save(remove: Boolean) {
        val value = try { if (remove) null else CallRelayConfig.parse(urls, username, password) }
        catch (error: IllegalArgumentException) { message = error.message; return }
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(value) }
                urls = ""; username = ""; password = ""
                onDismiss()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "The call relay could not be saved. Try again." }
            finally { saving = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("Call relay (TURN)") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Use a relay you trust when devices cannot connect directly. Set it on each device that needs it. Login details stay encrypted on this phone and are never added to room links. Leave and reopen the room after changing it.")
            OutlinedTextField(urls, { urls = it; message = null }, Modifier.fillMaxWidth(),
                label = { Text("Relay addresses") }, placeholder = { Text("turn:relay.example:3478") },
                supportingText = { Text("Separate addresses with commas. TLS: turns:relay.example:5349") },
                enabled = !loading && !saving, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(username, { username = it; message = null }, Modifier.fillMaxWidth(),
                label = { Text("Relay username") }, singleLine = true, enabled = !loading && !saving)
            OutlinedTextField(password, { password = it; message = null }, Modifier.fillMaxWidth(),
                label = { Text("Relay password") }, singleLine = true, enabled = !loading && !saving,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton({ save(true) }, enabled = !loading && !saving) { Text("Remove call relay") }
        }
    }, confirmButton = {
        TextButton({ save(false) }, enabled = !loading && !saving) { Text("Save call relay") }
    }, dismissButton = { TextButton(onDismiss, enabled = !saving) { Text("Cancel") } })
}
