package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
internal fun PersonaMenuItems(
    state: StartState, enabled: Boolean, actions: PersonaMenuActions, onChoose: (PersonaChoice) -> Unit,
) {
    for (persona in state.personas) {
        if (persona.pubkey == state.account?.pubkey) continue
        DropdownMenuItem(
            text = { Text(persona.personaName) },
            enabled = enabled,
            onClick = { onChoose(PersonaChoice.Saved(persona)) },
        )
    }
    if (mySignetOnDevice(state.signers) != null) {
        DropdownMenuItem(text = { Text("Add from My Signet") }, enabled = enabled, onClick = { onChoose(PersonaChoice.Add) })
    }
    if (state.signingIn) {
        DropdownMenuItem(text = { Text("Cancel sign-in") }, onClick = actions.cancel)
    }
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
