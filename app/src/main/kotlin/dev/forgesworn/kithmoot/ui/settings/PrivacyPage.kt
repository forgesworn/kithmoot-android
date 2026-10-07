package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.runtime.Composable

/** Privacy: whether public profiles are looked up, and what the app keeps private. */
@Composable
internal fun PrivacyPage(publicProfiles: Boolean, onPublicProfiles: (Boolean) -> Unit) {
    SettingsSection("Profiles") {
        SettingsSwitchRow("Show profile names and pictures", PROFILES_SUMMARY, checked = publicProfiles, onCheckedChange = onPublicProfiles)
    }
    SettingsSection("What stays private") {
        SettingsNote("The rooms you have saved and the identities you use in them are encrypted on this phone and left out of backups.")
        SettingsNote("Messages travel through relays encrypted. Forgetting a room on this phone does not delete its messages from relays.")
    }
}

/** Also the summary of the same switch in Room details, which writes the same preference. */
const val PROFILES_SUMMARY = "Looks up each person's public Nostr profile. The room's relays see whose profiles you look up, " +
    "and pictures load from wherever each person keeps them. People choose their own names and pictures."
