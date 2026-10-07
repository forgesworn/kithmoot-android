package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.forgesworn.kithmoot.session.WebAppAddress
import dev.forgesworn.kithmoot.ui.start.SiteAddressDialog

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
    SettingsSection(null) {
        SettingsNavRow("Relays",
            if (issues > 0) relaysNeedAttention(issues) else "The servers that pass on your rooms' encrypted messages", onClick = onRelays)
        if (signedIn) SettingsNavRow("Relays for private conversations",
            "Where one-to-one conversations with you are kept. This list is public.", onClick = onDmRelays)
        SettingsNavRow("KithMoot site",
            runCatching { WebAppAddress.parse(webAppAddress).origin.removePrefix("https://") }.getOrDefault(webAppAddress)) { siteOpen = true }
    }
    // Off by default: it needs a Bothy box that offers VMLS, and most people have none.
    vmlsPreview?.let { on ->
        SettingsSection("Your Bothy box (preview)") {
            SettingsSwitchRow("VMLS rooms and restore witness", VMLS_PREVIEW_SUMMARY, checked = on, onCheckedChange = onVmlsPreview)
        }
    }
    if (siteOpen) SiteAddressDialog(webAppAddress, onWebAppAddressChanged, onDismiss = { siteOpen = false })
}
