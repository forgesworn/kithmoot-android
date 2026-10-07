package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.account.ProfileMetadata
import dev.forgesworn.kithmoot.ui.StartState
import dev.forgesworn.kithmoot.ui.start.AccountSettingsActions
import kotlinx.serialization.json.JsonPrimitive

/** A profile field's key, its label and what to show under it. */
private class ProfileField(val key: String, val label: String, val hint: String? = null)

private val ProfileFields = listOf(
    ProfileField("name", "Username"),
    ProfileField("display_name", "Display name"),
    ProfileField("about", "About"),
    ProfileField("picture", "Profile picture address", "Starts with https://"),
    ProfileField("banner", "Banner image address", "Starts with https://"),
    ProfileField("website", "Website"),
    ProfileField("nip05", "Nostr address", "Like you@example.com"),
    ProfileField("lud16", "Lightning address", "Like you@wallet.example"),
)

/**
 * Editing the account's public Nostr profile. A body with no heading of its
 * own: the page title or the sheet's title names it.
 */
@Composable
internal fun ProfilePage(state: StartState, actions: AccountSettingsActions) {
    SettingsNote("These details are public on Nostr. Publishing updates your profile in other Nostr apps too.")
    if (state.profileBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp))
    val metadata = state.profileMetadata
    if (metadata != null) key(state.account?.pubkey, state.profileBaseId) {
        var values by remember { mutableStateOf(ProfileMetadata.fields.associateWith {
            (metadata[it] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content.orEmpty()
        }) }
        for (field in ProfileFields) OutlinedTextField(values[field.key].orEmpty(), { values = values + (field.key to it) },
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), label = { Text(field.label) },
            supportingText = field.hint?.let { hint -> { Text(hint) } }, enabled = !state.profileBusy,
            singleLine = field.key != "about", minLines = if (field.key == "about") 2 else 1)
        Button({ actions.publishProfile(values) }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).heightIn(min = 48.dp),
            enabled = !state.profileBusy) { Text("Publish profile") }
    }
    state.profileMessage?.let { SettingsNote(it, live = true) }
    SettingsActionRow("Reload profile", enabled = !state.profileBusy, onClick = actions.loadProfile)
}
