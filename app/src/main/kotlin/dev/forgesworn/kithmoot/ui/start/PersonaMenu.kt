package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.account.mySignetOnDevice
import dev.forgesworn.kithmoot.ui.AccountView
import dev.forgesworn.kithmoot.ui.StartState

data class PersonaMenuActions(
    val refreshSigners: () -> Unit,
    val add: (label: String, leaveRoom: Boolean) -> Unit,
    val switch: (pubkey: String, leaveRoom: Boolean) -> Unit,
    val cancel: () -> Unit,
)

internal sealed interface PersonaChoice {
    data object Add : PersonaChoice
    data class Saved(val persona: AccountView) : PersonaChoice
}

@Composable
internal fun CurrentPersona(account: AccountView, duplicateName: Boolean, roomIdentityNote: String? = null) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 280.dp)) {
        Text("Current persona", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(account.personaName, style = MaterialTheme.typography.titleMedium)
        // Both ends of the key, as everywhere else: the end alone hid the start people recognise.
        if (duplicateName) Text(account.short, style = MaterialTheme.typography.bodySmall)
        roomIdentityNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    HorizontalDivider()
}

@Composable
internal fun PersonaMenuItems(
    state: StartState, enabled: Boolean, actions: PersonaMenuActions, onChoose: (PersonaChoice) -> Unit,
) {
    val choices = state.personas.filter { it.pubkey != state.account?.pubkey }
    val canAdd = mySignetOnDevice(state.signers) != null
    val actionModifier = Modifier.heightIn(min = 56.dp)
    if (choices.isNotEmpty()) {
        Text("Switch persona", Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    for (persona in choices) {
        val duplicateName = state.account?.personaName == persona.personaName ||
            choices.any { it.pubkey != persona.pubkey && it.personaName == persona.personaName }
        DropdownMenuItem(
            text = {
                Column {
                    Text(persona.personaName, style = MaterialTheme.typography.bodyLarge)
                    if (duplicateName) Text(persona.short, style = MaterialTheme.typography.bodySmall)
                }
            },
            modifier = actionModifier,
            enabled = enabled,
            onClick = { onChoose(PersonaChoice.Saved(persona)) },
        )
    }
    if (canAdd) {
        DropdownMenuItem(text = { Text("Add from My Signet") }, modifier = actionModifier,
            enabled = enabled, onClick = { onChoose(PersonaChoice.Add) })
    }
    if (state.signingIn) {
        Text("Connecting to signer…", Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
        DropdownMenuItem(text = { Text("Cancel sign-in") }, modifier = actionModifier, onClick = actions.cancel)
    }
    if (choices.isNotEmpty() || canAdd || state.signingIn) HorizontalDivider()
}

@Composable
internal fun PersonaChoiceDialog(
    choice: PersonaChoice, inRoom: Boolean, actions: PersonaMenuActions, onDismiss: () -> Unit,
) {
    var label by rememberSaveable { mutableStateOf("") }
    val saved = choice as? PersonaChoice.Saved
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (saved == null) "Add from My Signet" else "Switch to ${saved.persona.personaName}?") },
        text = {
            Column {
                if (inRoom) Text("Switching leaves this room and ends any active call. Your draft stays with this persona.")
                if (saved == null) {
                    OutlinedTextField(
                        value = label, onValueChange = { label = it.take(48) }, singleLine = true,
                        label = { Text("Name on this phone (optional)") }, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                if (saved == null) actions.add(label, inRoom) else actions.switch(saved.persona.pubkey, inRoom)
            }) { Text(if (saved == null) "Open My Signet" else "Leave and switch") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
