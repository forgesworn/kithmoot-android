package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.media.CallIceServers
import dev.forgesworn.kithmoot.media.CallStunConfig
import dev.forgesworn.kithmoot.media.CallStunStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The old Connections row's line, kept for the build's own words: the person's own servers, or who answers when [own] is null because they set none. */
internal fun stunServerSummary(own: List<String>? = null): String =
    if (own == null) CallIceServers.defaultStunWords()
    else "Your own: " + own.joinToString(", ") { it.substringAfter(':').replace(Regex(":[0-9]+$"), "") }

/** The explanation for a build whose default is not Cloudflare. Words for the default come from the build, so a build that names another server says so. */
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

/** The STUN page's explanation: the spec's words for the stock Cloudflare default, the build's own for any other. */
internal fun stunPageNote(defaults: List<String> = CallIceServers.DEFAULT_STUN_URLS): String =
    if (defaults == listOf(CallIceServers.CLOUDFLARE_STUN)) CallPathWords.ADV_STUN_NOTE else stunServerExplanation(defaults)

/** The page's status line: who answers STUN now. */
internal fun stunPageStatus(own: List<String>?, defaults: List<String> = CallIceServers.DEFAULT_STUN_URLS): String = when {
    own != null -> CallPathWords.advUsing(own.firstOrNull()?.let(CallPathWords::hostOf) ?: own.first())
    defaults == listOf(CallIceServers.CLOUDFLARE_STUN) -> CallPathWords.ADV_USING_DEFAULT
    defaults.isEmpty() -> "Using no STUN server."
    else -> "Using " + CallIceServers.defaultStunWords(defaults).removeSuffix(" unless you set your own") + "."
}

/** Settings, Calls, Advanced: the device's own STUN server. */
@Composable
internal fun CallAdvancedPage() {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { CallStunStore(context) }
    val scope = rememberCoroutineScope()
    var urls by rememberSaveable { mutableStateOf("") }
    var own by remember { mutableStateOf<List<String>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var problem by rememberSaveable { mutableStateOf(false) }
    var unreadable by remember { mutableStateOf(false) }
    LaunchedEffect(store) {
        try {
            val saved = withContext(Dispatchers.IO) { store.read() }
            own = saved?.urls
            if (urls.isEmpty()) urls = saved?.urls?.joinToString(", ") ?: ""
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { unreadable = true; problem = true; message = CallPathWords.ADV_UNREADABLE }
        finally { loading = false }
    }
    fun save(remove: Boolean) {
        val value = try { if (remove) null else CallStunConfig.parse(urls) }
        catch (error: IllegalArgumentException) { message = error.message; problem = true; return }
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(value) }
                own = value?.urls
                unreadable = false
                if (value == null) urls = ""
                problem = false
                message = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { problem = true; message = "The STUN server could not be saved. Try again." }
            finally { saving = false }
        }
    }
    val idle = !loading && !saving
    SettingsSection(null) {
        if (!unreadable) SettingsStaticRow(stunPageStatus(own), live = true)
        Column(Modifier.fillMaxWidth().padding(horizontal = RowHorizontal, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(urls, { urls = it; message = null }, Modifier.fillMaxWidth(),
                label = { Text(CallPathWords.ADV_STUN) },
                supportingText = { Text("Separate addresses with commas. Leave empty for the default.") },
                isError = problem && message != null, enabled = idle,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done))
            Button({ save(false) }, Modifier.heightIn(min = 48.dp), enabled = idle) { Text(CallPathWords.ADV_SAVE) }
        }
        message?.let { SettingsNote(it, live = true) }
        if (own != null || unreadable) SettingsActionRow(CallPathWords.ADV_USE_DEFAULT, enabled = idle) { save(true) }
        SettingsNote(stunPageNote())
    }
}
