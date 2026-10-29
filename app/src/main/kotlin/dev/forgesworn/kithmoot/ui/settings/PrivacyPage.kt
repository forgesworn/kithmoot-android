package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Privacy: whether public profiles are looked up, who is blocked on this
 *  phone, and what the app keeps private. [blocked] is (key, name) pairs,
 *  newest first; null hides the section (nobody signed in). */
@Composable
internal fun PrivacyPage(publicProfiles: Boolean, onPublicProfiles: (Boolean) -> Unit,
    blocked: List<Pair<String, String>>? = null, onUnblock: (String) -> Unit = {},
    hideIpOnCalls: Boolean = false, onHideIpOnCalls: (Boolean) -> Unit = {},
    sendDeliveryReceipts: Boolean = true, onSendDeliveryReceipts: (Boolean) -> Unit = {},
    /** The Where files go row's subtitle; null hides the row. */
    filesSummary: String? = null, onFiles: () -> Unit = {},
    accountNetworkError: String? = null,
    accountNetworkMode: dev.forgesworn.kithmoot.account.AccountNetworkMode = dev.forgesworn.kithmoot.account.AccountNetworkMode.NOSTR,
    onAccountNetworkMode: (dev.forgesworn.kithmoot.account.AccountNetworkMode) -> Unit = {}) {
    SettingsSection("Account networking") {
        accountNetworkError?.let { SettingsNote(it) }
        val nostr = accountNetworkMode == dev.forgesworn.kithmoot.account.AccountNetworkMode.NOSTR
        SettingsSwitchRow("Use Nostr account networking",
            "Enabling connects to your chosen Nostr relays using account and room identities. Relays can see which keys you look up and where you connect. " +
                "Public profile pictures contact each picture's host. This choice applies to every account on this installation.",
            checked = nostr, onCheckedChange = { onAccountNetworkMode(if (it)
                dev.forgesworn.kithmoot.account.AccountNetworkMode.NOSTR else dev.forgesworn.kithmoot.account.AccountNetworkMode.PRIVATE_INVITATIONS) })
        SettingsNote(if (nostr) "Nostr account networking is enabled. Your saved profile preference applies."
            else "Private invitations: Nostr contacts, inbox discovery, room sync and public profile lookups are paused. " +
                "Your keys, saved rooms and pending changes stay on this device. Private groups and paired nodes keep working. " +
                "Signer connections and update checks are separate.")
    }
    SettingsSection("Profiles") {
        SettingsSwitchRow("Show other people’s profile names and pictures", PROFILES_SUMMARY, checked = publicProfiles, onCheckedChange = onPublicProfiles)
        SettingsNote("Your signed-in account’s own name and picture are loaded separately while Nostr account networking is enabled. Turning off other people’s profiles keeps your own account name. Private invitations pauses these lookups too.")
    }
    if (filesSummary != null) SettingsSection("Files") {
        SettingsNavRow(dev.forgesworn.kithmoot.ui.room.WHERE_FILES_GO, filesSummary, onClick = onFiles)
    }
    SettingsSection(dev.forgesworn.kithmoot.ui.DeliveryWords.SETTING_SECTION) {
        SettingsSwitchRow(dev.forgesworn.kithmoot.ui.DeliveryWords.SETTING_LABEL, dev.forgesworn.kithmoot.ui.DeliveryWords.SETTING_SUMMARY,
            checked = sendDeliveryReceipts, onCheckedChange = onSendDeliveryReceipts)
    }
    SettingsSection("Calls") {
        SettingsSwitchRow(HIDE_IP_LABEL, HIDE_IP_SUMMARY, checked = hideIpOnCalls, onCheckedChange = onHideIpOnCalls)
    }
    if (blocked != null) SettingsSection("Blocked on this phone") {
        SettingsNote("They can’t invite you or ask to join your rooms on this phone. They aren’t told, and nothing is published. " +
            "A private chat already open with them stays until you leave it.")
        if (blocked.isEmpty()) SettingsNote("Nobody. Block somebody from an invitation’s ⋮ or from People.")
        for ((key, name) in blocked) Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = RowHorizontal, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            TextButton({ onUnblock(key) }, Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Unblock $name" }) { Text("Unblock") }
        }
    }
    SettingsSection("What stays private") {
        SettingsNote("The rooms you have saved and the identities you use in them are encrypted on this phone and left out of backups.")
        SettingsNote("Messages travel through relays encrypted. Forgetting a room on this phone does not delete its messages from relays.")
    }
}

/** The web's words for the same switch (kithmoot #499). */
const val HIDE_IP_LABEL = "Hide my IP address on calls"
const val HIDE_IP_SUMMARY = "Calls go through a relay server, so other people on the call don’t see your IP address. It can be slower."

/** Also the summary of the same switch in Room details, which writes the same preference. */
const val PROFILES_SUMMARY = "Looks up public Nostr profiles for people in rooms and your private chat list. The room's relays see whose profiles you look up, " +
    "and pictures load from wherever each person keeps them. People choose their own names and pictures."
