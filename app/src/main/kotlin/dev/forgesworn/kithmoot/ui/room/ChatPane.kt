package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.account.npubOf
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.protocol.Lane
import dev.forgesworn.kithmoot.session.QuietTransport
import dev.forgesworn.kithmoot.session.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ChatPane(
    messages: List<ChatMessage>,
    selfParticipant: String,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    onReact: (ChatMessage, String) -> Unit = { _, _ -> },
    onOpenPrivateConversation: (ChatMessage) -> Unit = {},
    profilesEnabled: Boolean = false,
    profiles: Map<String, PublicProfile> = emptyMap(),
    onProfilesEnabled: (Boolean) -> Unit = {},
    /** The lane the next message will take; null when the room cannot say. */
    lane: Lane? = null,
    /** A quiet room, and whether this device may post in it. See session/QuietTransport.kt. */
    quiet: Boolean = false,
    quietCanSend: Boolean = true,
    /** False while a room key transition is incomplete or terminal. */
    canSend: Boolean = true,
    sending: Boolean = false,
    sendError: String? = null,
    showTitle: Boolean = true,
    searchOpen: Boolean = false,
    onCloseSearch: () -> Unit = {},
    onReadingChanged: (Boolean) -> Unit = {},
) {
    var expandedImage by remember { mutableStateOf<ChatAttachment?>(null) }
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var query by rememberSaveable { mutableStateOf("") }
    var emojiOpen by remember { mutableStateOf(false) }
    var privacyOpen by remember { mutableStateOf(false) }
    var localSearchOpen by rememberSaveable { mutableStateOf(false) }
    var reactionTarget by remember { mutableStateOf<ChatMessage?>(null) }
    val searching = searchOpen || localSearchOpen
    LaunchedEffect(searching) { if (!searching) query = "" }
    val listState = rememberLazyListState()
    val readingCallback by rememberUpdatedState(onReadingChanged)
    LaunchedEffect(listState, searching) {
        snapshotFlow { !searching && !listState.canScrollForward }.collect { readingCallback(it) }
    }
    DisposableEffect(Unit) { onDispose { readingCallback(false) } }
    var lastMessageId by rememberSaveable { mutableStateOf<String?>(null) }
    // The log as a person reads it: the latest edit's words on each message,
    // a retracted one shown as such, replies under the message they answer.
    // See Messages.kt and docs/messages.md in the reference implementation.
    val resolved = resolveConversation(messages)
    val privateInvitations = messages.asReversed().filter { message ->
        val invite = message.invite
        invite != null && (message.participant == selfParticipant || invite.to == selfParticipant)
    }.distinctBy { it.invite!!.room }.take(5).reversed()
    val rows = buildList {
        for (r in resolved.stream) {
            add(r to false)
            for (reply in r.replies) add(reply to true)
        }
    }
    val conversation = rows.map { it.first.shown }
    val visible = rows.filter { (r, _) ->
        val message = r.shown
        when {
            r.retracted -> query.isBlank()
            query.isBlank() -> true
            else -> listOf(message.body, message.name.orEmpty(), message.participant, profiles[message.participant]?.name.orEmpty())
                .any { it.contains(query.trim(), ignoreCase = true) }
        }
    }
    LaunchedEffect(conversation.lastOrNull()?.id, query) {
        val latest = conversation.lastOrNull()
        if (query.isBlank() && latest != null && latest.id != lastMessageId) {
            val wasAtEnd = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?.let { it >= visible.lastIndex - 1 } == true
            if (lastMessageId == null || wasAtEnd || latest.participant == selfParticipant) {
                listState.animateScrollToItem(visible.lastIndex)
            }
            lastMessageId = latest.id
        }
    }
    fun send() {
        if (canSend && !sending && draft.text.isNotBlank()) { onSend(draft.text); draft = TextFieldValue("") }
    }
    Column(modifier.fillMaxWidth().imePadding()) {
        if (privateInvitations.isNotEmpty()) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                privateInvitations.forEach { message ->
                    val invite = message.invite ?: return@forEach
                    val peer = if (message.participant == selfParticipant) invite.to else message.participant
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Private conversation", style = MaterialTheme.typography.titleSmall)
                                Text("With ${shortId(peer)} · ${messageTime(message.sentAt)}", style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = { onOpenPrivateConversation(message) }) { Text("Open") }
                        }
                    }
                }
            }
        }
        if (showTitle) Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Chat", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { localSearchOpen = !localSearchOpen }) { Icon(Icons.Filled.Search, "Search messages") }
        }
        Row(Modifier.fillMaxWidth().clickable(onClick = { privacyOpen = true }).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text("Encrypted" + when (lane) { Lane.PUBLIC -> " · public relays"; Lane.SHELTERED -> " · circle relays"; Lane.DIRECT -> " · direct"; null -> " · checking connection" } + if (quiet) " · quiet" else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "Message privacy. " + (lane?.meaning ?: "Transport unknown.") })
        }
        if (quiet && !quietCanSend) Text(QuietTransport.CANNOT_SEND, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        if (searching) {
            OutlinedTextField(query, { query = it.take(200) }, Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                label = { Text("Search messages or people") }, singleLine = true,
                trailingIcon = {
                    if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") }
                    else IconButton(onClick = { localSearchOpen = false; onCloseSearch() }) { Icon(Icons.Filled.Close, "Close search") }
                })
            Text("Searches messages loaded on this device", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
        }
        Box(Modifier.weight(1f)) {
            if (visible.isEmpty()) Text(if (query.isBlank()) "Nothing said yet." else "No matching messages.", Modifier.align(Alignment.Center).padding(20.dp))
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(visible, key = { _, row -> row.first.original.id }) { index, (r, nested) ->
                    val message = r.shown
                    val mine = message.participant == selfParticipant
                    val addressed = !r.retracted && mentionedBy(message, selfParticipant)
                    val previous = visible.getOrNull(index - 1)?.first?.shown
                    val senderHeader = previous == null || previous.participant != message.participant || message.sentAt - previous.sentAt > 300 || messageDate(previous.sentAt) != messageDate(message.sentAt)
                    if (index == 0 || messageDate(visible[index - 1].first.shown.sentAt) != messageDate(message.sentAt)) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(messageDate(message.sentAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(start = if (nested) 24.dp else 0.dp),
                        horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                        Surface(shape = RoundedCornerShape(16.dp),
                            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.widthIn(max = 320.dp).combinedClickable(
                                onClick = { reactionTarget = r.original }, onClickLabel = "Message details and reactions",
                                onLongClick = { reactionTarget = r.original }, onLongClickLabel = "React to message",
                            )) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                if (!mine && senderHeader) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (profilesEnabled) ProfileAvatar(message.participant, message.name, profiles[message.participant], Modifier.size(24.dp))
                                    Text(message.name ?: profiles[message.participant]?.name ?: shortNpub(message.participant),
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (addressed) Text("Mentioned you", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(if (r.retracted) "Message retracted" else message.body, style = MaterialTheme.typography.bodyLarge,
                                    color = if (r.retracted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                if (!r.retracted) message.attachments.forEach { attachment ->
                                    TextButton(onClick = { expandedImage = attachment }) {
                                        Text("Open attachment: ${attachment.name ?: "Image"}")
                                    }
                                }
                                val meta = listOfNotNull(messageClock(message.sentAt), r.original.lane?.chip,
                                    if (r.edited && !r.retracted) "edited" else null, if (nested || r.orphan) "reply" else null)
                                Text(meta.joinToString(" · "), Modifier.align(Alignment.End).padding(top = 3.dp), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (!r.retracted) {
                            val updates = reactionUpdates(messages, r.original)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                REACTION_EMOJIS.forEach { emoji ->
                                    val active = updates.filter { it.reaction!!.emoji == emoji && it.reaction.active }
                                    if (active.isNotEmpty()) {
                                        val selected = active.any { it.participant == selfParticipant }
                                        FilterChip(selected = selected, onClick = { onReact(r.original, emoji) }, enabled = canSend,
                                            label = { Text("$emoji ${active.size}") },
                                            modifier = Modifier.semantics { contentDescription = "${if (selected) "Remove" else "Add"} $emoji reaction, ${active.size}" })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = draft, onValueChange = { if (it.text.length <= MAX_CHAT_TEXT_LENGTH) draft = it }, modifier = Modifier.weight(1f), enabled = canSend,
                shape = RoundedCornerShape(24.dp),
                leadingIcon = { IconButton(onClick = { emojiOpen = true }, enabled = canSend) { Icon(Icons.Filled.EmojiEmotions, "Emoji") } },
                placeholder = { Text("Say something") }, maxLines = 4, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
            IconButton(onClick = { send() }, enabled = canSend && draft.text.isNotBlank() && !sending, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
        if (sending) Text("Waiting for relay confirmation…", Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        sendError?.let { Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    expandedImage?.let { attachment ->
        if (messages.any { it.attachments.contains(attachment) } && resolved.stream.flatMap { listOf(it) + it.replies }.any { !it.retracted && it.shown.attachments.contains(attachment) }) {
            AttachmentViewer(attachment, onClose = { expandedImage = null })
        } else LaunchedEffect(attachment) { expandedImage = null }
    }
    if (emojiOpen) EmojiDialog(onDismiss = { emojiOpen = false }) { emoji ->
        val start = draft.selection.min; val end = draft.selection.max
        val text = draft.text.replaceRange(start, end, emoji)
        if (text.length <= MAX_CHAT_TEXT_LENGTH) { draft = TextFieldValue(text, TextRange(start + emoji.length)); emojiOpen = false }
    }
    if (privacyOpen) AlertDialog(onDismissRequest = { privacyOpen = false }, title = { Text("Message privacy") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Messages are encrypted to the room.")
            Text(lane?.meaning ?: "The transport for the next message is not yet known.")
            if (quiet) Text(QuietTransport.MEANING)
            Text("Public profiles are optional. Lookups share participant keys with room relays; picture hosts see image requests. Names and pictures are self-reported.")
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(profilesEnabled, onProfilesEnabled); Text("Show public profiles") }
        } }, confirmButton = { TextButton(onClick = { privacyOpen = false }) { Text("Done") } })
    reactionTarget?.let { target ->
        val resolvedTarget = resolved.stream.flatMap { listOf(it) + it.replies }.find { it.original.id == target.id }
        val bodyText = if (resolvedTarget?.retracted == true) null else resolvedTarget?.shown?.body ?: target.body
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        val context = androidx.compose.ui.platform.LocalContext.current
        AlertDialog(onDismissRequest = { reactionTarget = null }, title = { Text("Message") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (target.participant == selfParticipant) "You" else target.name ?: profiles[target.participant]?.name ?: "Participant", style = MaterialTheme.typography.titleSmall)
                Text(npubOf(target.participant), style = MaterialTheme.typography.bodySmall)
                Text(messageTime(target.sentAt), style = MaterialTheme.typography.bodySmall)
                target.lane?.let { Text(it.meaning, style = MaterialTheme.typography.bodySmall) }
                if (bodyText != null) androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(bodyText, style = MaterialTheme.typography.bodyMedium)
                }
                if (canSend && resolvedTarget?.retracted != true) FlowRow {
                    REACTION_EMOJIS.forEach { emoji ->
                        val active = reactionUpdates(messages, target).filter { it.reaction!!.emoji == emoji && it.reaction.active }
                        val selected = active.any { it.participant == selfParticipant }
                        TextButton(onClick = { onReact(target, emoji); reactionTarget = null },
                            modifier = Modifier.semantics { contentDescription = "${if (selected) "Remove" else "Add"} $emoji reaction, ${active.size}" }) { Text(emoji) }
                    }
                }
            }
        }, dismissButton = {
            if (bodyText != null) TextButton(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(bodyText))
                // Android 13+ shows its own clipboard confirmation toast; a
                // second one here would just be noise.
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                    android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                }
                reactionTarget = null
            }) { Text("Copy text") }
        }, confirmButton = { TextButton(onClick = { reactionTarget = null }) { Text("Done") } })
    }
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

private fun messageDate(seconds: Long): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(seconds * 1000))
private fun messageClock(seconds: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(seconds * 1000))
