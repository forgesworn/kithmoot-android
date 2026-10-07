package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.relay.RelayChoice
import dev.forgesworn.kithmoot.relay.RelaySelection
import dev.forgesworn.kithmoot.ui.StartState
import dev.forgesworn.kithmoot.ui.start.AccountSettingsActions

private const val MAX_RELAYS = 16
const val RELAYS_SAVED = "Saved on this phone. Account sync is reconnecting."

/**
 * The relay list being edited, held above the page so the bar's Save and the
 * discard question on the way out can see it, and kept across rotation.
 * [dirty] is whether it differs from what was last saved, so putting a change
 * back is not an unsaved edit.
 */
@Stable
class RelayEditorState(initial: List<RelayChoice>, draft: List<RelayChoice> = initial, message: String? = null, failed: Boolean = false) {
    var committed by mutableStateOf(initial)
        private set
    var draft by mutableStateOf(draft)
    /** The last save's result: confirmation, or the reason it was refused. */
    var message by mutableStateOf(message)
    var failed by mutableStateOf(failed)
    val dirty: Boolean get() = draft != committed

    fun edit(next: List<RelayChoice>) { draft = next; message = null; failed = false }

    fun save(actions: AccountSettingsActions) {
        val refusal = actions.saveRelays(draft)
        if (refusal == null) { committed = draft; message = RELAYS_SAVED; failed = false } else { message = refusal; failed = true }
    }

    fun discard() { draft = committed; message = null; failed = false }

    companion object {
        val Saver: Saver<RelayEditorState, Any> = listSaver(
            save = { listOf(encode(it.committed), encode(it.draft), it.message.orEmpty(), it.failed.toString()) },
            restore = { RelayEditorState(decode(it[0]), decode(it[1]), (it[2] as String).ifEmpty { null }, (it[3] as String).toBoolean()) },
        )
        private fun encode(list: List<RelayChoice>) = list.joinToString("\n") { "${it.read}|${it.write}|${it.url}" }
        private fun decode(text: String) = text.lines().filter { it.isNotEmpty() }.map {
            val (read, write, url) = it.split('|', limit = 3)
            RelayChoice(url, read.toBoolean(), write.toBoolean())
        }
    }
}

/** An editor for [choices]; a different account's list starts afresh. */
@Composable
fun rememberRelayEditor(choices: List<RelayChoice>, account: String?): RelayEditorState =
    rememberSaveable(account, saver = RelayEditorState.Saver) { RelayEditorState(choices) }

/**
 * Relays: which servers pass on your rooms' messages. A page body with no
 * heading of its own, so Settings' page and the room menu's sheet both host it.
 * In a room it is read-only, which still shows how each relay is doing.
 */
@Composable
internal fun RelaysPage(state: StartState, editor: RelayEditorState, inRoom: Boolean, actions: AccountSettingsActions) {
    var adding by rememberSaveable { mutableStateOf("") }
    var publish by remember { mutableStateOf(false) }
    val editable = !inRoom && !state.busy && !state.profileBusy
    val draft = editor.draft
    val candidate = adding.trim()
    val candidateValid = candidate.isNotEmpty() && runCatching { RelaySelection.validate(listOf(RelayChoice(candidate))) }.isSuccess
    val atLimit = draft.size >= MAX_RELAYS

    SettingsNote("Relays pass on your rooms' messages, encrypted. Read means KithMoot fetches from a relay; Write means it sends to it. Untick both to stop using a relay on this phone.")
    if (inRoom) SettingsNote("Leave the room to change relays. You can still check how they are doing here.")

    Column(Modifier.fillMaxWidth()) {
        for ((index, relay) in draft.withIndex()) {
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
            val health = state.relayHealth.entries.firstOrNull { it.key.removeSuffix("/") == relay.url.removeSuffix("/") }?.value
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(relay.url, style = MaterialTheme.typography.bodyLarge)
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                Text(if (!relay.read && !relay.write) "Disabled" else health?.connection ?: "Not connected",
                    style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (relay.read) Text("Read: ${health?.read ?: "Not checked"}", style = MaterialTheme.typography.bodyMedium, color = muted)
                if (relay.write) Text("Write: ${health?.write ?: "Not checked"}", style = MaterialTheme.typography.bodyMedium, color = muted)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RelayToggle("Read", "Read from ${relay.url}", relay.read, editable) { editor.edit(draft.toMutableList().also { list -> list[index] = relay.copy(read = it) }) }
                    RelayToggle("Write", "Write to ${relay.url}", relay.write, editable) { editor.edit(draft.toMutableList().also { list -> list[index] = relay.copy(write = it) }) }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                        TextButton({ editor.edit(draft.filterIndexed { i, _ -> i != index }) }, enabled = editable,
                            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Remove relay ${relay.url}" }) { Text("Remove") }
                    }
                }
            }
        }
    }

    OutlinedTextField(
        adding, { adding = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        label = { Text("Add a relay") }, placeholder = { Text("wss://relay.example") }, singleLine = true,
        enabled = editable && !atLimit, isError = candidate.isNotEmpty() && !candidateValid,
        supportingText = if (candidate.isNotEmpty() && !candidateValid) { { Text("Use a relay address that starts with wss://") } } else null,
    )
    if (atLimit) SettingsNote("You can use up to $MAX_RELAYS relays.")
    TextButton({ editor.edit(draft + RelayChoice(candidate)); adding = "" },
        Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp), enabled = editable && candidateValid && !atLimit) { Text("Add") }
    Button({ editor.save(actions) }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).heightIn(min = 48.dp),
        enabled = editable && editor.dirty) { Text("Save relay choices") }
    editor.message?.let { message ->
        Text(message, style = MaterialTheme.typography.bodyMedium,
            color = if (editor.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
    }

    if (state.account != null) {
        SettingsSection("Your public relay list") {
            SettingsNote("Optional. Publishes these relays as your public Nostr relay list, so other Nostr apps can use them. Relays you have disabled are left out.")
            SettingsActionRow("Publish public relay list", enabled = editable && !editor.dirty) { publish = true }
            state.profileMessage?.let { SettingsNote(it, live = true) }
        }
        SettingsSection("Account sync") {
            SettingsNote("Account sync uses these relays. Saved rooms keep the relays their invitations name; your read and write choices apply to matching relays when you reopen a room.")
            SettingsActionRow("Reconnect and retry sync", enabled = editable && !editor.dirty, onClick = actions.retrySync)
        }
    }

    SettingsSection("Advanced") {
        SettingsDisclosure("Circle relays") {
            OutlinedTextField(state.circleBoxes, actions.circleBoxes, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), enabled = editable,
                label = { Text("Your circle's relays, one per line") }, minLines = 2)
            SettingsNote("Only add relays that you or people in your circle run. Messages that travel only through them are labelled “circle relays” in the chat.")
        }
    }

    if (publish) AlertDialog(onDismissRequest = { publish = false }, title = { Text("Publish your relay list?") },
        text = { Text("These relay addresses and your read and write choices will be public on Nostr.") },
        confirmButton = { TextButton({ publish = false; actions.publishRelays() }) { Text("Publish relay list") } },
        dismissButton = { TextButton({ publish = false }) { Text("Cancel") } })
}

/** One of a relay's Read and Write boxes: the whole row, label included, is the target. */
@Composable
private fun RelayToggle(label: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .minimumInteractiveComponentSize().semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, Modifier.padding(start = 8.dp, end = 8.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Asked when leaving the relay list with changes that are not saved. */
@Composable
internal fun DiscardRelayChangesDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(onDismissRequest = onKeep, title = { Text("Discard your relay changes?") },
        text = { Text("Your changes to this list haven't been saved.") },
        confirmButton = { TextButton(onDiscard) { Text("Discard") } },
        dismissButton = { TextButton(onKeep) { Text("Keep editing") } })
}
