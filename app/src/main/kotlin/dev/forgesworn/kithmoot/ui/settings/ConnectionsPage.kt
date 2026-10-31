package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.forgesworn.kithmoot.session.WebAppAddress
import dev.forgesworn.kithmoot.ui.start.SiteAddressDialog

/**
 * The page titled Relays (its enum name, [SettingsPage.CONNECTIONS], is older
 * than its title): the Nostr relays that carry your rooms, where private
 * conversations are kept, the Bothy switch, and the site invitations point at.
 * How calls connect is on Calls.
 */
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
        SettingsNavRow(NOSTR_RELAYS_TITLE,
            if (issues > 0) relaysNeedAttention(issues) else "The servers that pass on your rooms' encrypted messages", onClick = onRelays)
        if (signedIn) SettingsNavRow("Relays for private chats",
            "Where one-to-one chats with you are kept. This list is public.", onClick = onDmRelays)
    }
    // Off by default: it needs a Bothy box that offers VMLS, and most people have none.
    vmlsPreview?.let { on ->
        SettingsSection(bothySettingsTitle) {
            SettingsSwitchRow(if (admin2ProductSelected) "Enable private groups and restore witness" else "VMLS rooms and restore witness",
                if (admin2ProductSelected) "Manual private invitations with designated device admins on your Bothy home. Off until you turn it on." else VMLS_PREVIEW_SUMMARY, checked = on, onCheckedChange = onVmlsPreview)
        }
    }
    SettingsSection(null) {
        SettingsNavRow("KithMoot site",
            runCatching { WebAppAddress.parse(webAppAddress).origin.removePrefix("https://") }.getOrDefault(webAppAddress)) { siteOpen = true }
    }
    if (siteOpen) SiteAddressDialog(webAppAddress, onWebAppAddressChanged, onDismiss = { siteOpen = false })
}

/** The child page of Relays that lists the Nostr relays themselves. */
const val NOSTR_RELAYS_TITLE = "Nostr relays"
