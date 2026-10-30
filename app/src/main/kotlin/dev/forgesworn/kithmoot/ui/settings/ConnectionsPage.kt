package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
    accountKey: String? = null,
    sprySyncStatus: String = "",
    sprySyncAvailable: Boolean = false,
    onSyncSpry: () -> Unit = {},
    onUnpairSpry: () -> Unit = {},
    /** Finding public call relays: null hides the row. */
    publicCallRelays: PublicCallRelaysPanel? = null,
    /** Debug builds only: the built-in call relay's row; null hides it. */
    builtInRelayDebug: BuiltInRelayDebug? = null,
) {
    var siteOpen by rememberSaveable { mutableStateOf(false) }
    var callRelayOpen by rememberSaveable { mutableStateOf(false) }
    var stunOpen by rememberSaveable { mutableStateOf(false) }
    var spryOpen by rememberSaveable { mutableStateOf(false) }
    var publicOpen by rememberSaveable { mutableStateOf(false) }
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
        SettingsNavRow("Spry", "Pair your node once, then share relay access automatically in calls") { spryOpen = true }
        SettingsNavRow("Other call relays", "Set up another relay manually") { callRelayOpen = true }
        SettingsNavRow(STUN_SERVER_TITLE, stunSummary) { stunOpen = true }
    }
    publicCallRelays?.let { panel ->
        val view by panel.view.collectAsState()
        LaunchedEffect(panel, publicOpen) { if (!publicOpen) panel.peek() }
        SettingsSection(PublicCallRelaysCopy.TITLE) {
            SettingsNavRow(PublicCallRelaysCopy.TITLE, publicCallRelaysSummary(view)) { publicOpen = true }
        }
        if (publicOpen) PublicCallRelaysDialog(panel) { publicOpen = false }
    }
    if (dev.forgesworn.kithmoot.BuildConfig.DEBUG) builtInRelayDebug?.let { BuiltInRelayDebugSection(it) }
    // Off by default: it needs a Bothy box that offers VMLS, and most people have none.
    vmlsPreview?.let { on ->
        SettingsSection(bothySettingsTitle) {
            SettingsSwitchRow(if (admin2ProductSelected) "Enable private groups and restore witness" else "VMLS rooms and restore witness",
                if (admin2ProductSelected) "Manual private invitations with designated device admins on your Bothy home. Off until you turn it on." else VMLS_PREVIEW_SUMMARY, checked = on, onCheckedChange = onVmlsPreview)
        }
    }
    if (siteOpen) SiteAddressDialog(webAppAddress, onWebAppAddressChanged, onDismiss = { siteOpen = false })
    if (callRelayOpen) CallRelayDialog { callRelayOpen = false }
    if (spryOpen) SpryDialog(accountKey, sprySyncStatus, sprySyncAvailable, onSyncSpry, onUnpairSpry) { spryOpen = false }
    if (stunOpen) StunServerDialog { stunOpen = false }
}
