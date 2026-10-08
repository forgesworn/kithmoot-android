package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.Icons
import kotlinx.coroutines.launch
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import dev.forgesworn.kithmoot.session.PendingChat
import dev.forgesworn.kithmoot.session.PendingChatState
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
    onSend: (String, () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    onReact: (ChatMessage, String) -> Unit = { _, _ -> },
    onOpenPrivateConversation: (ChatMessage) -> Unit = {},
    memberPackAvailable: () -> Boolean = { false },
    unlockMemberPacks: suspend () -> Boolean = { false },
    attachments: List<ChatAttachment> = emptyList(),
    mediaBusy: Boolean = false,
    onAddImage: (android.net.Uri, String, Boolean) -> Unit = { _, _, _ -> },
    onRemoveAttachment: (String) -> Unit = {},
    privateConversationPeers: List<String> = emptyList(),
    onMessagePrivately: (String) -> Unit = {},
    profilesEnabled: Boolean = false,
    profiles: Map<String, PublicProfile> = emptyMap(),
    onProfilesEnabled: (Boolean) -> Unit = {},
    /** The lane the next message will take; null when the room cannot say. */
    lane: Lane? = null,
    /** A Tor-only (anonymous) room: said beside the lane, which stays public. */
    torOnly: Boolean = false,
    /** Relays the room has reached; a Tor-only room with none can show no history. */
    relaysUp: Int = 1,
    /** A quiet room, and whether this device may post in it. See session/QuietTransport.kt. */
    quiet: Boolean = false,
    quietCanSend: Boolean = true,
    /** False while a room key transition is incomplete or terminal. */
    canSend: Boolean = true,
    sending: Boolean = false,
    /** Messages kept on this phone and not yet in the log: shown after the last message, as the sender's own. */
    pendingChats: List<PendingChat> = emptyList(),
    /** The end of a room that self-destructs: a kept message that cannot leave before it says so. */
    destructEndsAt: Long? = null,
    onRetryPending: () -> Unit = {},
    /** Puts the message's text back in the composer through the callback, dropping the kept message. */
    onEditPending: (String, (String) -> Unit) -> Unit = { _, _ -> },
    onDeletePending: (String) -> Unit = {},
    onRemovePending: (String) -> Unit = {},
    sendError: String? = null,
    showTitle: Boolean = true,
    searchOpen: Boolean = false,
    onCloseSearch: () -> Unit = {},
    onReadingChanged: (Boolean) -> Unit = {},
    /** Bumped when a notification for this room is tapped: back to the latest message. */
    latestRequest: Int = 0,
    /** Lines nobody typed, such as who renamed the room, shown in time order. */
    notes: List<dev.forgesworn.kithmoot.ui.RoomNote> = emptyList(),
) {
    var expandedImage by remember { mutableStateOf<ChatAttachment?>(null) }
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var deletePendingId by remember { mutableStateOf<String?>(null) }
    var removePendingId by remember { mutableStateOf<String?>(null) }
    var editPendingId by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var emojiOpen by remember { mutableStateOf(false) }
    var mediaOpen by remember { mutableStateOf(false) }
    var privacyOpen by remember { mutableStateOf(false) }
    var localSearchOpen by rememberSaveable { mutableStateOf(false) }
    var moreReactionTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var profileTarget by remember { mutableStateOf<ChatMessage?>(null) }
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
    // A kept message leaves the end of the chat once the log itself shows it, not on a relay's say-so.
    val shownPending = pendingChats.filter { kept -> messages.none { it.id == kept.id || it.id == kept.messageId } }
    val lastIndex = visible.lastIndex + shownPending.size
    // A note follows the last conversation (a message and its replies) that
    // began no later than it; notes from before the first message lead.
    // Hidden while searching, which looks for what people said.
    val shownNotes = if (query.isBlank()) notes.sortedWith(compareBy({ it.sentAt }, { it.id })) else emptyList()
    val tops = visible.indices.filter { !visible[it].second }
    val notesAfter = mutableMapOf<Int, MutableList<dev.forgesworn.kithmoot.ui.RoomNote>>()
    val leadingNotes = mutableListOf<dev.forgesworn.kithmoot.ui.RoomNote>()
    for (note in shownNotes) {
        val top = tops.lastOrNull { visible[it].first.shown.sentAt <= note.sentAt }
        if (top == null) { leadingNotes += note; continue }
        val end = (tops.firstOrNull { it > top } ?: visible.size) - 1
        notesAfter.getOrPut(end) { mutableListOf() } += note
    }
    fun noteText(note: dev.forgesworn.kithmoot.ui.RoomNote): String {
        val who = if (note.participant == selfParticipant) "You" else
            messages.lastOrNull { it.participant == note.participant && it.name != null }?.name
                ?: profiles[note.participant]?.name ?: shortNpub(note.participant)
        return "$who renamed the room to “${note.name}”"
    }
    @Composable fun NoteLine(note: dev.forgesworn.kithmoot.ui.RoomNote) {
        Text(noteText(note), Modifier.fillMaxWidth().padding(vertical = 4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // Opening a conversation shows its latest message, and keeps showing it
    // while history loads in around it and new messages arrive, until the
    // person scrolls up to read. History arrives in batches from several
    // relays, and the list keeps whatever was on screen in place as each
    // lands, so scrolling once at the first message used to leave a long
    // conversation open somewhere near its start.
    var following by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) following = !listState.canScrollForward
        }
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(visible.size, shownPending.size, conversation.lastOrNull()?.id, query) {
        val latest = conversation.lastOrNull()
        if (query.isBlank() && latest != null && visible.isNotEmpty()) {
            if (latest.id != lastMessageId && latest.participant == selfParticipant && lastMessageId != null) following = true
            if (following) listState.scrollToItem(lastIndex)
            lastMessageId = latest.id
        } else if (query.isBlank() && shownPending.isNotEmpty() && following) listState.scrollToItem(lastIndex)
    }
    LaunchedEffect(latestRequest) {
        if (latestRequest > 0 && query.isBlank() && (visible.isNotEmpty() || shownPending.isNotEmpty())) {
            following = true
            listState.scrollToItem(lastIndex)
        }
    }
    fun send() {
        if (canSend && !sending && !mediaBusy && (draft.text.isNotBlank() || attachments.isNotEmpty())) {
            val submitted = draft.text
            onSend(submitted) { if (draft.text == submitted) draft = TextFieldValue("") }
        }
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
            Text(privacyLine(lane, torOnly, quiet),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "Message privacy. " + (privacyMeaning(lane, torOnly) ?: "Transport unknown.") })
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
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Inside the first row rather than an item of their own, so a
                // row's index stays its item index for the scrolling below.
                if (visible.isEmpty() && leadingNotes.isNotEmpty()) item(key = "room-notes-leading") { Column { leadingNotes.forEach { NoteLine(it) } } }
                itemsIndexed(visible, key = { _, row -> row.first.original.id }) { index, (r, nested) ->
                    if (index == 0) leadingNotes.forEach { NoteLine(it) }
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
                                if (!mine && senderHeader) Row(modifier = Modifier.heightIn(min = 44.dp).clickable(
                                    onClickLabel = "View participant details", onClick = { profileTarget = message }), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (profilesEnabled) ProfileAvatar(message.participant, message.name, profiles[message.participant], Modifier.size(24.dp))
                                    Text(message.name ?: profiles[message.participant]?.name ?: shortNpub(message.participant),
                                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (addressed) Text("Mentioned you", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                // A link takes its own tap; anywhere else the bubble's tap and hold still open reactions.
                                PackMessageText(if (r.retracted) "Message retracted" else message.body, style = MaterialTheme.typography.bodyLarge,
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
                            FlowRow(modifier = Modifier.offset(y = (-10).dp).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                updates.map { it.reaction!!.emoji }.distinct().forEach { emoji ->
                                    val active = updates.filter { it.reaction!!.emoji == emoji && it.reaction.active }
                                    if (active.isNotEmpty()) {
                                        val selected = active.any { it.participant == selfParticipant }
                                        FilterChip(selected = selected, onClick = { onReact(r.original, emoji) }, enabled = canSend,
                                            shape = RoundedCornerShape(24.dp), colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
                                            label = { Row(verticalAlignment = Alignment.CenterVertically) { PackEmoji(emoji, Modifier.size(24.dp)); Text(" ${active.size}") } },
                                            modifier = Modifier.semantics { contentDescription = "${if (selected) "Remove" else "Add"} $emoji reaction, ${active.size}" })
                                    }
                                }
                            }
                        }
                    }
                    notesAfter[index]?.forEach { NoteLine(it) }
                }
                items(shownPending, key = { "pending-" + it.id }) { kept ->
                    val doomed = destructEndsAt != null && dev.forgesworn.kithmoot.session.pendingDoomed(
                        destructEndsAt, true, kept.state == PendingChatState.SENDING, rememberNow())
                    PendingRow(kept, canSend, onRetryPending, doomed = doomed,
                        onEdit = { if (draft.text.isBlank()) onEditPending(kept.id) { draft = TextFieldValue(it, TextRange(it.length)) } else editPendingId = kept.id },
                        onDelete = { deletePendingId = kept.id }, onRemove = { removePendingId = kept.id })
                }
            }
            // Drawn after the list, so it sits above it. Under the empty list
            // filling the box, the text was missing from the accessibility
            // tree that UI Automator reads.
            if (visible.isEmpty() && shownNotes.isEmpty() && shownPending.isEmpty()) Text(emptyChat(query, torOnly, relaysUp), Modifier.align(Alignment.Center).padding(20.dp))
            if (!following && query.isBlank() && visible.isNotEmpty()) {
                SmallFloatingActionButton(
                    onClick = { following = true; scope.launch { listState.scrollToItem(lastIndex) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                ) { Icon(Icons.Filled.KeyboardArrowDown, "Jump to the latest message") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        if (mediaOpen || mediaBusy || attachments.isNotEmpty()) MediaComposer(canSend && !torOnly, mediaBusy, attachments, onAddImage, onRemoveAttachment) { text ->
            val combined = draft.text + (if (draft.text.isBlank()) "" else "\n") + text
            require(combined.length <= MAX_CHAT_TEXT_LENGTH) { "Shorten your message before adding this file’s credit." }
            draft = TextFieldValue(combined, TextRange(combined.length))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = draft, onValueChange = { if (it.text.length <= MAX_CHAT_TEXT_LENGTH) draft = it }, modifier = Modifier.weight(1f), enabled = canSend,
                shape = RoundedCornerShape(24.dp),
                leadingIcon = { IconButton(onClick = { emojiOpen = true }, enabled = canSend) { Icon(Icons.Filled.EmojiEmotions, "Emoji") } },
                trailingIcon = { IconButton(onClick = { mediaOpen = !mediaOpen }, enabled = canSend && !torOnly) { Icon(Icons.Filled.AttachFile, "Images, GIFs and stickers") } },
                placeholder = { Text("Say something") }, maxLines = 4, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
            IconButton(onClick = { send() }, enabled = canSend && (draft.text.isNotBlank() || attachments.isNotEmpty()) && !sending && !mediaBusy, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
        sendError?.let { Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    deletePendingId?.let { id ->
        AlertDialog(onDismissRequest = { deletePendingId = null },
            title = { Text("Delete this message?") },
            text = { Text("It has not left this phone. Nobody will see it.") },
            confirmButton = { TextButton(onClick = { deletePendingId = null; onDeletePending(id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deletePendingId = null }) { Text("Keep") } })
    }
    removePendingId?.let { id ->
        AlertDialog(onDismissRequest = { removePendingId = null },
            title = { Text("Remove from the list?") },
            text = { Text("A relay may have received this message, and people may still see it. Removing it here does not unsend it.") },
            confirmButton = { TextButton(onClick = { removePendingId = null; onRemovePending(id) }) { Text("Remove from list") } },
            dismissButton = { TextButton(onClick = { removePendingId = null }) { Text("Keep") } })
    }
    editPendingId?.let { id ->
        AlertDialog(onDismissRequest = { editPendingId = null },
            title = { Text("Replace what you have typed?") },
            text = { Text("Editing this message puts it in the box below, in place of your unsent text.") },
            confirmButton = { TextButton(onClick = { editPendingId = null; onEditPending(id) { draft = TextFieldValue(it, TextRange(it.length)) } }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { editPendingId = null }) { Text("Keep typing") } })
    }
    expandedImage?.let { attachment ->
        if (messages.any { it.attachments.contains(attachment) } && resolved.stream.flatMap { listOf(it) + it.replies }.any { !it.retracted && it.shown.attachments.contains(attachment) }) {
            AttachmentViewer(attachment, onClose = { expandedImage = null })
        } else LaunchedEffect(attachment) { expandedImage = null }
    }
    profileTarget?.let { target ->
        ParticipantDetails(target.participant, target.name, if (profilesEnabled) profiles[target.participant] else null,
            onClose = { profileTarget = null },
            onMessage = if (target.participant in privateConversationPeers) ({ profileTarget = null; onMessagePrivately(target.participant) }) else null)
    }
    if (emojiOpen) EmojiDialog(onDismiss = { emojiOpen = false }, memberPackAvailable, unlockMemberPacks) { emoji ->
        val start = draft.selection.min; val end = draft.selection.max
        val text = draft.text.replaceRange(start, end, emoji)
        if (text.length <= MAX_CHAT_TEXT_LENGTH) { draft = TextFieldValue(text, TextRange(start + emoji.length)); emojiOpen = false }
    }
    if (privacyOpen) AlertDialog(onDismissRequest = { privacyOpen = false }, title = { Text("Message privacy") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Messages are encrypted to the room.")
            Text(privacyMeaning(lane, torOnly) ?: "The transport for the next message is not yet known.")
            if (quiet) Text(QuietTransport.MEANING)
            Text("Public profiles are optional. Lookups share participant keys with room relays; picture hosts see image requests. Names and pictures are self-reported.")
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(profilesEnabled, onProfilesEnabled); Text("Show public profiles") }
        } }, confirmButton = { TextButton(onClick = { privacyOpen = false }) { Text("Done") } })
    moreReactionTarget?.let { target ->
        if (!canSend || resolved.byKey[MessageRef(target.id, target.participant).key]?.retracted == true) {
            LaunchedEffect(target) { moreReactionTarget = null }
        } else EmojiDialog(onDismiss = { moreReactionTarget = null }, memberPackAvailable, unlockMemberPacks) { emoji ->
            onReact(target, emoji); moreReactionTarget = null
        }
    }
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
                    TextButton(onClick = { moreReactionTarget = target; reactionTarget = null }) { Text("More emoji…") }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmojiDialog(onDismiss: () -> Unit, memberPackAvailable: () -> Boolean, unlockMemberPacks: suspend () -> Boolean, choose: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var available by remember { mutableStateOf(memberPackAvailable()) }
    var status by remember { mutableStateOf("") }
    var unlocking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose an emoji") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text("Search emoji") }, singleLine = true)
            TextButton(enabled = !unlocking, onClick = {
                unlocking = true; status = "Confirm this account in your signer…"
                scope.launch {
                    try { available = unlockMemberPacks(); status = if (available) "Nostr pack unlocked." else "No member packs found for this account." }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (failure: Exception) { status = failure.message ?: "The pack could not be unlocked." }
                    finally { unlocking = false }
                }
            }) { Text("Unlock Nostr packs") }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                item { FlowRow { (if (available && memberPackAvailable()) CULT_EMOJIS + EmojiCatalog.entries else EmojiCatalog.entries).map { (emoji, words) -> emoji to if (emoji == "🤦") "facepalm head against wall frustrated $words" else words }.filter { (emoji, words) -> "$emoji $words".contains(query, true) }.take(120).forEach { (emoji, words) ->
                    TextButton(onClick = { choose(emoji) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "$emoji $words" }) { PackEmoji(emoji, Modifier.size(32.dp)) }
                } } }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

internal fun messageTime(seconds: Long): String = SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(seconds * 1000))

private fun messageDate(seconds: Long): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(seconds * 1000))
private fun messageClock(seconds: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(seconds * 1000))

/**
 * What an empty chat says. A Tor-only room keeps no messages on the phone
 * (P6-02, decided 3 October), so with no relay reached an empty room is not
 * yet known to be empty: say where its history comes from.
 */
internal fun emptyChat(query: String, torOnly: Boolean, relaysUp: Int): String = when {
    query.isNotBlank() -> "No matching messages."
    torOnly && relaysUp == 0 -> "This Tor-only room keeps no messages on this phone. Earlier messages come from its relays and show once one answers."
    else -> "Nothing said yet."
}


/** What a kept message says about itself, with the same honesty as the web app: nothing is called unsent that may have gone. */
internal fun pendingStatus(state: PendingChatState): String = when (state) {
    PendingChatState.WAITING -> "Pending: will send when you are connected."
    PendingChatState.SENDING -> "Sending…"
    PendingChatState.REFUSED -> "Not sent: the relays turned it down. Trying again shortly."
    PendingChatState.UNKNOWN -> "Not confirmed: no relay answered in time. Trying again shortly."
    PendingChatState.MOVED -> "Not sent: this conversation changed its key before it went. Copy it into the conversation to send it."
}

/** A message the person wrote that no relay has confirmed, at the end of the chat in the shape of their own. */
@Composable
private fun PendingRow(kept: PendingChat, canSend: Boolean, onRetry: () -> Unit, doomed: Boolean = false, onEdit: () -> Unit, onDelete: () -> Unit, onRemove: () -> Unit) {
    val sending = kept.state == PendingChatState.SENDING
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
            modifier = Modifier.widthIn(max = 320.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(kept.text.ifEmpty { "Message kept on this phone" }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(messageClock(kept.sentAt), Modifier.align(Alignment.End).padding(top = 3.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(if (doomed) dev.forgesworn.kithmoot.session.WILL_NOT_BE_SENT else pendingStatus(kept.state), Modifier.widthIn(max = 320.dp).padding(horizontal = 4.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        if (!sending) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (kept.state != PendingChatState.MOVED) TextButton(onClick = onRetry, enabled = canSend) { Text("Retry") }
            // Nothing has left the phone: it can be taken back or dropped, and dropping it means nobody sees it.
            if (kept.state.clean && kept.editable) TextButton(onClick = onEdit) { Text("Edit") }
            if (kept.state.clean) TextButton(onClick = onDelete) { Text("Delete") }
            else TextButton(onClick = onRemove) { Text("Remove from list") }
        }
    }
}
