package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.forgesworn.kithmoot.media.CallStunStore
import dev.forgesworn.kithmoot.session.WebAppAddress
import dev.forgesworn.kithmoot.ui.start.SiteAddressDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Connections: the relays that carry your rooms, where private conversations are kept, and the site invitations point at. */
@Composable
internal fun ConnectionsPage(
    issues: Int,
    signedIn: Boolean,
    webAppAddress: String,
    onWebAppAddressChanged: (String) -> Boolean,
    onRelays: () -> Unit,
    onDmRelays: () -> Unit,
    /** VMLS rooms and the restore witness: null hides the switch. */
    vmlsPreview: Boolean? = null,
    onVmlsPreview: (Boolean) -> Unit = {},
) {
    var siteOpen by rememberSaveable { mutableStateOf(false) }
    var callRelayOpen by rememberSaveable { mutableStateOf(false) }
    var stunOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current.applicationContext
    // Read again whenever the dialog closes, so the row shows what calls will use.
    var stunSummary by remember { mutableStateOf(stunServerSummary()) }
    LaunchedEffect(stunOpen) {
        if (stunOpen) return@LaunchedEffect
        stunSummary = try {
            stunServerSummary(withContext(Dispatchers.IO) { CallStunStore(context).read() }?.urls)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { STUN_SERVER_UNREADABLE_SUMMARY }
    }
    SettingsSection(null) {
        SettingsNavRow("Relays",
            if (issues > 0) relaysNeedAttention(issues) else "The servers that pass on your rooms' encrypted messages", onClick = onRelays)
        if (signedIn) SettingsNavRow("Relays for private chats",
            "Where one-to-one conversations with you are kept. This list is public.", onClick = onDmRelays)
        SettingsNavRow("KithMoot site",
            runCatching { WebAppAddress.parse(webAppAddress).origin.removePrefix("https://") }.getOrDefault(webAppAddress)) { siteOpen = true }
        SettingsNavRow("Call relay (TURN)", "Your relay address and login for calls on this device") { callRelayOpen = true }
        SettingsNavRow(STUN_SERVER_TITLE, stunSummary) { stunOpen = true }
    }
    // Off by default: it needs a Bothy box that offers VMLS, and most people have none.
    vmlsPreview?.let { on ->
        SettingsSection("Your Bothy box (preview)") {
            SettingsSwitchRow("VMLS rooms and restore witness", VMLS_PREVIEW_SUMMARY, checked = on, onCheckedChange = onVmlsPreview)
        }
    }
    if (siteOpen) SiteAddressDialog(webAppAddress, onWebAppAddressChanged, onDismiss = { siteOpen = false })
    if (callRelayOpen) CallRelayDialog { callRelayOpen = false }
    if (stunOpen) StunServerDialog { stunOpen = false }
}
