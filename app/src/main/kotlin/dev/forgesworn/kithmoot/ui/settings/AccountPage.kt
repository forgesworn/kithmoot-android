package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.ui.StartState
import dev.forgesworn.kithmoot.ui.room.ProfileAvatar
import dev.forgesworn.kithmoot.ui.start.AccountActions
import dev.forgesworn.kithmoot.ui.start.AccountRoomActions
import dev.forgesworn.kithmoot.ui.start.AccountSection
import dev.forgesworn.kithmoot.ui.start.AccountSettingsActions
import dev.forgesworn.kithmoot.ui.start.AccountSyncSection
import dev.forgesworn.kithmoot.ui.start.SignOutDialog

/**
 * Account: who this phone is signed in as, its public profile, the rooms kept
 * in the account and, last, signing out. With no account but a preview one still
 * on the phone, it is the way back to that account instead.
 */
@Composable
internal fun AccountPage(
    state: StartState,
    signIn: AccountActions,
    accountSettings: AccountSettingsActions,
    accountRooms: AccountRoomActions,
    importableCount: Int,
    onEditProfile: () -> Unit,
) {
    val account = state.account
    if (account == null) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            AccountSection(state, signIn, enabled = !state.busy, showHeading = false)
        }
        return
    }
    var signingOut by remember { mutableStateOf(false) }
    var rendezvousIndex by remember(account.pubkey) { mutableStateOf("") }

    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ProfileAvatar(account.pubkey, account.name, account.profile, Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            Text(account.shownName, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { contentDescription = "Public key ${account.npub}" })
            // With no name, the line above already is the npub; printing it twice reads as two keys.
            if (account.shownName != account.short) {
                Text(account.short, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
            }
        }
    }
    SettingsNote(when (account.method) {
        "nip55" -> "Signing with ${account.signerLabel ?: "a signer app"} on this phone. Rooms you open are joined as this account."
        "bunker" -> "Signing through your remote signer. Rooms you open are joined as this account; the first signature in a room needs your signer to be reachable."
        else -> "Signing with a key kept in this app's encrypted vault. Rooms you open are joined as this account."
    })

    SettingsSection("Profile") {
        SettingsNavRow("Edit public profile", "Your name, picture and about. Anyone on Nostr can see them.", onClick = onEditProfile)
    }

    AccountSyncSection(state.roomBookmarks, importableCount, enabled = !state.busy && !state.roomSyncBusy, accountRooms)

    if (account.method == "bunker") SettingsSection("Advanced") {
        SettingsDisclosure("Heartwood rendezvous key") {
            SettingsNote("For Heartwood signers only. Asks your Heartwood to give this phone the rendezvous key it derives from your account. " +
                "Enter the index exactly as it is set on your Heartwood. KithMoot never stores this key with your account, rooms or contacts.")
            state.rendezvous?.activeIndex?.let { active ->
                SettingsNote("This phone holds index $active. Enter a different index to change it.")
            }
            OutlinedTextField(
                value = rendezvousIndex,
                onValueChange = { rendezvousIndex = it.filter(Char::isDigit).take(10) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                label = { Text("Rendezvous index") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            val requestedIndex = rendezvousIndex.toLongOrNull()?.takeIf { it in 0..0xffffffffL }
            val rendezvousBusy = state.rendezvous?.busy == true
            Button(
                onClick = { requestedIndex?.let(signIn.onProvisionRendezvous) },
                enabled = !state.busy && !rendezvousBusy && requestedIndex != null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).heightIn(min = 48.dp),
            ) { Text(if (rendezvousBusy) "Waiting for Heartwood…" else "Ask Heartwood") }
            state.rendezvous?.message?.let { SettingsNote(it, live = true) }
        }
    }

    SettingsSection(null) {
        SettingsActionRow("Sign out", destructive = true,
            enabled = !state.busy && !state.profileBusy && !state.roomSyncBusy && !state.projectsBusy) { signingOut = true }
    }
    if (signingOut) SignOutDialog(inRoom = false, onConfirm = { signingOut = false; accountSettings.signOut() }, onDismiss = { signingOut = false })
}
