package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.media.CallRelayConfig
import dev.forgesworn.kithmoot.media.CallRelayStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings, Calls, Relay server: the TURN login the person runs or rents.
 * Addresses and username survive rotation; the password stays out of saved
 * instance state (the activity handles rotation itself, so it is kept there
 * too, but never written to a bundle).
 */
@Composable
internal fun RelayServerPage() {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { CallRelayStore(context) }
    val scope = rememberCoroutineScope()
    var urls by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    // Hidden each time the page opens; the eye shows it on request.
    var showPassword by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    /** What is stored: null when nothing is, so the head and Remove… follow the real state. */
    var saved by remember { mutableStateOf<CallRelayConfig?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var problem by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf(false) }
    var unreadable by remember { mutableStateOf(false) }
    LaunchedEffect(store) {
        try {
            val value = withContext(Dispatchers.IO) { store.read() }
            saved = value
            // Typed text from before a rotation is kept rather than replaced.
            if (urls.isEmpty() && username.isEmpty() && password.isEmpty()) {
                urls = value?.urls?.joinToString(", ") ?: ""
                username = value?.username ?: ""
                password = value?.credential ?: ""
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { unreadable = true; problem = true; message = CallPathWords.SRV_UNREADABLE }
        finally { loading = false }
    }
    fun save() {
        val value = try { CallRelayConfig.parse(urls, username, password) }
        catch (error: IllegalArgumentException) { message = error.message; problem = true; return }
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(value) }
                saved = value
                unreadable = false
                problem = false
                message = if (value == null) CallPathWords.SRV_REMOVED else CallPathWords.SRV_SAVED
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { problem = true; message = "The relay server could not be saved. Try again." }
            finally { saving = false }
        }
    }
    fun remove() {
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(null) }
                saved = null; unreadable = false
                urls = ""; username = ""; password = ""
                problem = false
                message = CallPathWords.SRV_REMOVED
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { problem = true; message = "The relay server could not be removed. Try again." }
            finally { saving = false }
        }
    }
    val idle = !loading && !saving
    SettingsSection(null) {
        SettingsNote(CallPathWords.SRV_LEAD)
        saved?.let { SettingsStaticRow(CallPathWords.srvOnHead(it.urls.firstOrNull()?.let(CallPathWords::hostOf)), CallPathWords.SRV_ON, live = true) }
        Column(Modifier.fillMaxWidth().padding(horizontal = RowHorizontal, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(urls, { urls = it; message = null }, Modifier.fillMaxWidth(),
                label = { Text(CallPathWords.SRV_ADDR) },
                supportingText = { Text(CallPathWords.SRV_ADDR_HINT) },
                isError = problem && message != null && message != CallPathWords.SRV_UNREADABLE,
                enabled = idle, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(username, { username = it; message = null }, Modifier.fillMaxWidth(),
                label = { Text(CallPathWords.SRV_USER) }, singleLine = true, enabled = idle,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
            OutlinedTextField(password, { password = it; message = null }, Modifier.fillMaxWidth(),
                label = { Text(CallPathWords.SRV_PASS) }, singleLine = true, enabled = idle,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = {
                    // One name with an on/off state, as the web's eye button has.
                    IconToggleButton(showPassword, { showPassword = it }, enabled = idle) {
                        Icon(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = CallPathWords.SRV_SHOW_PASS)
                    }
                })
            Button(::save, Modifier.heightIn(min = 48.dp), enabled = idle) { Text(CallPathWords.SRV_SAVE) }
        }
        message?.let { text ->
            SettingsNote(text, live = true)
        }
        if (saved != null || unreadable) SettingsActionRow(CallPathWords.SRV_REMOVE, destructive = true, enabled = idle) { removing = true }
        SettingsNote(CallPathWords.SRV_PRIVACY)
        if (saved != null) SettingsNote(CallPathWords.SRV_REOPEN)
    }
    if (removing) AlertDialog(
        onDismissRequest = { removing = false },
        title = { Text(CallPathWords.SRV_RM_TITLE) },
        text = { Text(CallPathWords.SRV_RM_MSG) },
        dismissButton = { TextButton({ removing = false }) { Text(CallPathWords.CANCEL) } },
        confirmButton = { TextButton({ removing = false; remove() }) { Text(CallPathWords.SRV_RM_BTN, color = MaterialTheme.colorScheme.error) } },
    )
}
