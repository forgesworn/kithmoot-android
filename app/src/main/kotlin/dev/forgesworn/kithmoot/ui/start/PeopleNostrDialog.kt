package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.account.publicKeyFrom
import dev.forgesworn.kithmoot.account.npubOf
import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.ui.StartState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PeopleNostrDialog(state: StartState, actions: PeopleActions, invite: (String, String) -> Unit, initialPeer: String?, close: () -> Unit) {
    val self = state.account?.pubkey ?: return
    val inbox = state.nostrPeople
    var peer by remember(self, initialPeer) { mutableStateOf(initialPeer) }
    var configuring by remember(self) { mutableStateOf(false) }
    var composing by remember(self, initialPeer) { mutableStateOf(initialPeer != null) }
    val thread = inbox.threads.find { it.peer == peer }
    val known = peopleSections(state, "").let { (it.contacts + it.following + it.conversations.filter { person -> person.chatId != null }).associateBy { p -> p.pubkey } }
    fun name(key: String): String = known[key]?.let { personLabel(it, state.followProfiles) } ?: state.followProfiles[key]?.name ?: shortNpub(key)
    LaunchedEffect(self, initialPeer) {
        if (initialPeer != null && thread != null) actions.markNostrRead(thread.messages.mapTo(HashSet()) { it.id })
    }
    val sending = inbox.sending
    // Results from before this sheet opened belong to another conversation, unless still waiting.
    val opened = remember(self) { inbox.sends to inbox.sent }
    val sendResult = inbox.sendStatus.takeIf { it.isNotEmpty() && (inbox.pending || inbox.sends != opened.first) }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(thread?.let { name(it.peer) } ?: "Nostr messages", style = MaterialTheme.typography.titleLarge)
            sendResult?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (inbox.status.isNotEmpty()) Text(inbox.status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (configuring) {
                Text("Choose one to three public Nostr relays that accept encrypted messages for your account.")
                var relays by remember(state.dmRelays) { mutableStateOf(state.dmRelays.orEmpty().joinToString("\n")) }
                OutlinedTextField(relays, { relays = it }, label = { Text("Published inbox relays") }, maxLines = 4,
                    modifier = Modifier.fillMaxWidth(), enabled = !state.profileBusy)
                state.profileMessage?.let { Text(it) }
                Row {
                    TextButton({ configuring = false }) { Text("Back") }
                    Button({ actions.saveNostrRelays(relays.split(Regex("[,\\s]+")).filter(String::isNotBlank)) },
                        enabled = !state.profileBusy && relays.isNotBlank()) { Text("Publish relays") }
                }
            } else if (thread != null || composing) {
                var recipient by remember(self, peer) { mutableStateOf(peer?.let(::npubOf).orEmpty()) }
                var draft by remember(self, peer) { mutableStateOf("") }
                val target = thread?.peer ?: publicKeyFrom(recipient)?.takeUnless { it == self }
                TextButton({ peer = null; composing = false }) { Text("Back to messages") }
                if (thread != null) {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(thread.messages, key = { it.id }) { message ->
                            Column {
                                Text(if (message.author == self) "You" else name(thread.peer), style = MaterialTheme.typography.labelMedium)
                                Text(message.text)
                            }
                        }
                    }
                    if (thread.unread > 0) TextButton({ actions.markNostrRead(thread.messages.mapTo(HashSet()) { it.id }) }) { Text("Mark as read") }
                } else {
                    OutlinedTextField(recipient, { recipient = it }, label = { Text("Recipient npub") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                OutlinedTextField(draft, { if (it.length <= 4096) draft = it }, label = { Text("Nostr message") },
                    maxLines = 3, modifier = Modifier.fillMaxWidth(), enabled = !sending && !inbox.pending)
                Row {
                    Button({
                        if (target != null) actions.sendNostr(target, draft, thread?.messages?.lastOrNull()?.id)
                    }, enabled = target != null && draft.isNotBlank() && !sending && !inbox.pending) { Text("Send on Nostr") }
                    if (thread != null) TextButton({ actions.block(thread.peer); peer = null }) { Text("Block sender") }
                }
                if (thread != null) TextButton({ invite(thread.peer, name(thread.peer)) }, enabled = state.contactInbox.ready) {
                    Text("Invite to KithMoot chat")
                }
                LaunchedEffect(inbox.sent) {
                    if (inbox.sent != opened.second) {
                        draft = ""
                        peer = target
                        composing = false
                    }
                }
            } else {
                if (!inbox.enabled) {
                    Text("The Nostr inbox is turned off on this device.")
                    Button({ actions.enableNostrInbox(true) }) { Text("Turn on inbox") }
                } else {
                    Row {
                        TextButton(actions.refreshNostrInbox) { Text("Refresh") }
                        TextButton({ actions.enableNostrInbox(false) }) { Text("Turn off inbox") }
                    }
                    TextButton({ composing = true }, enabled = !inbox.pending) { Text("New Nostr message") }
                }
                TextButton({ configuring = true; actions.loadNostrRelays() }) { Text("Inbox relays") }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(inbox.threads, key = { it.peer }) { row ->
                        val label = name(row.peer)
                        val knownPerson = row.peer in known
                        val request = !knownPerson && row.messages.none { it.author == self }
                        val profile = state.followProfiles[row.peer].takeIf { state.publicProfiles && knownPerson }
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable {
                            peer = row.peer
                            actions.markNostrRead(row.messages.mapTo(HashSet()) { it.id })
                        }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            PersonAvatar(row.peer, label, profile)
                            Column(Modifier.weight(1f)) {
                                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (request) Text("Message request", style = MaterialTheme.typography.labelMedium)
                            }
                            if (row.unread > 0) Text("${row.unread} unread", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            if (inbox.pending) TextButton({ actions.sendNostr("", "", null) }, enabled = !sending) { Text("Retry waiting message") }
            TextButton(close) { Text("Close") }
        }
    }
}
