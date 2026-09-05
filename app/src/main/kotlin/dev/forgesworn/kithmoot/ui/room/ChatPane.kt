package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.session.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatPane(
    messages: List<ChatMessage>,
    selfParticipant: String,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    onReact: (ChatMessage, String) -> Unit = { _, _ -> },
    profilesEnabled: Boolean = false,
    profiles: Map<String, PublicProfile> = emptyMap(),
    onProfilesEnabled: (Boolean) -> Unit = {},
) {
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var query by remember { mutableStateOf("") }
    var emojiOpen by remember { mutableStateOf(false) }
    var profileSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val conversation = messages.filter { it.reaction == null }
    val visible = conversation.filter { message ->
        query.isBlank() || listOf(message.body, message.name.orEmpty(), message.participant, profiles[message.participant]?.name.orEmpty())
            .any { it.contains(query.trim(), ignoreCase = true) }
    }
    LaunchedEffect(conversation.size, query) {
        if (query.isBlank() && visible.isNotEmpty()) listState.animateScrollToItem(visible.lastIndex)
    }
    fun send() {
        if (draft.text.isNotBlank()) { onSend(draft.text); draft = TextFieldValue("") }
    }
    Column(modifier.fillMaxWidth().imePadding()) {
        Text("Chat", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.headlineSmall)
        Text("Encrypted to the room. Search covers loaded messages on this device.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(query, { query = it.take(200) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            label = { Text("Search messages or people") }, singleLine = true,
            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") } })
        TextButton(onClick = { profileSettings = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Profile pictures: ${if (profilesEnabled) "on" else "off"}")
        }
        Box(Modifier.weight(1f)) {
            if (visible.isEmpty()) Text(if (query.isBlank()) "Nothing said yet." else "No matching messages.", Modifier.align(Alignment.Center).padding(20.dp))
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(visible, key = { it.id }) { message ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProfileAvatar(message.participant, message.name, profiles[message.participant], Modifier.size(32.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                val name = if (message.participant == selfParticipant) "You" else message.name ?: profiles[message.participant]?.name
                                Text(listOfNotNull(name, shortId(message.participant)).joinToString(" · "), style = MaterialTheme.typography.labelMedium)
                                Text(messageTime(message.sentAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(message.body, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyLarge)
                        val updates = reactionUpdates(messages, message)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            REACTION_EMOJIS.forEach { emoji ->
                                val active = updates.filter { it.reaction!!.emoji == emoji && it.reaction.active }
                                if (emoji in listOf("👍", "❤️", "🤦") || active.isNotEmpty()) {
                                    val mine = active.any { it.participant == selfParticipant }
                                    FilterChip(selected = mine, onClick = { onReact(message, emoji) },
                                        label = { Text(emoji + if (active.isEmpty()) "" else " ${active.size}") },
                                        modifier = Modifier.semantics { contentDescription = "${if (mine) "Remove" else "Add"} $emoji reaction, ${active.size}" })
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = draft, onValueChange = { if (it.text.length <= MAX_CHAT_TEXT_LENGTH) draft = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("Say something") }, maxLines = 4, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
            IconButton(onClick = { send() }, enabled = draft.text.isNotBlank(), modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
        TextButton(onClick = { emojiOpen = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("😊 Emoji") }
    }
    if (emojiOpen) EmojiDialog(onDismiss = { emojiOpen = false }) { emoji ->
        val start = draft.selection.min; val end = draft.selection.max
        val text = draft.text.replaceRange(start, end, emoji)
        if (text.length <= MAX_CHAT_TEXT_LENGTH) { draft = TextFieldValue(text, TextRange(start + emoji.length)); emojiOpen = false }
    }
    if (profileSettings) AlertDialog(onDismissRequest = { profileSettings = false }, title = { Text("Public profile pictures") },
        text = { Column {
            Text("Look up public Nostr profiles for this visit. Room relays will see participant keys, and picture hosts will see image requests. Names and pictures are self-reported. A new room identity may have no public profile.")
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(profilesEnabled, onProfilesEnabled); Text("Look up public profiles") }
        } }, confirmButton = { TextButton(onClick = { profileSettings = false }) { Text("Done") } })
}

private val EMOJIS = listOf("👍" to "thumbs up yes like", "❤️" to "heart love", "🤦" to "facepalm head against wall", "😂" to "laugh tears joy", "😊" to "smile happy", "🎉" to "party celebration", "👀" to "eyes", "🙏" to "thanks please", "😢" to "sad cry", "🤯" to "mind blown", "🙄" to "eye roll", "😅" to "sweat smile", "🔥" to "fire", "👏" to "clap applause", "💯" to "hundred", "✅" to "done check", "❌" to "cross no", "🤔" to "thinking", "👋" to "wave hello", "🤗" to "hug", "😍" to "heart eyes", "😡" to "angry", "💔" to "broken heart", "🍻" to "cheers", "☕" to "coffee", "🚀" to "rocket", "💪" to "muscle", "🤞" to "fingers crossed")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmojiDialog(onDismiss: () -> Unit, choose: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose an emoji") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text("Search emoji") }, singleLine = true)
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                item { FlowRow { EMOJIS.filter { (emoji, words) -> "$emoji $words".contains(query, true) }.forEach { (emoji, words) ->
                    TextButton(onClick = { choose(emoji) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "$emoji $words" }) { Text(emoji, style = MaterialTheme.typography.headlineSmall) }
                } } }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

internal fun messageTime(seconds: Long): String = SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(seconds * 1000))
