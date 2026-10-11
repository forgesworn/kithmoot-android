package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
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
    recordingDrafts: List<dev.forgesworn.kithmoot.media.recording.RecordingShareDraft> = emptyList(),
    onRemoveRecordingDraft: (String) -> Unit = {},
    onSendRecordingDraft: (String) -> Unit = {},
    recordingStorageChoice: dev.forgesworn.kithmoot.media.recording.RecordingStorageChoice? = null,
    onPrepareRecordingStorage: (String, String) -> Unit = { _, _ -> },
    onUploadRecordingDraft: (String, String, Boolean) -> Unit = { _, _, _ -> },
    recordingUploadRunning: Boolean = false,
    onCancelRecordingUpload: () -> Unit = {},
    artwork: List<ChatArtwork> = emptyList(),
    onAddArtwork: (ChatArtwork) -> Unit = {},
    onRemoveArtwork: (Int) -> Unit = {},
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
    internetAllowed: Boolean = true,
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
    targetMessage: dev.forgesworn.kithmoot.session.MessageRef? = null,
    targetRequest: Int = 0,
    onClearTarget: () -> Unit = {},
) {
    var expandedImage by remember { mutableStateOf<ChatAttachment?>(null) }
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue("")) }
    var deletePendingId by remember { mutableStateOf<String?>(null) }
    var removePendingId by remember { mutableStateOf<String?>(null) }
    var editPendingId by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var emojiOpen by remember { mutableStateOf(false) }
    var artworkSearchOpen by remember { mutableStateOf(false) }
    var artworkStartTab by remember { mutableStateOf(ArtworkTab.EMOJI) }
    var emojiSkinTone by rememberSaveable { mutableIntStateOf(0) }
    var mediaOpen by remember { mutableStateOf(false) }
    val inputKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val inputFocus = androidx.compose.ui.platform.LocalFocusManager.current
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
        fun replies(r: dev.forgesworn.kithmoot.session.ResolvedMessage) {
            for (reply in r.replies) { add(reply to true); replies(reply) }
        }
        for (r in resolved.stream) {
            add(r to false)
            replies(r)
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
    var targetWaited by remember(targetMessage, targetRequest) { mutableStateOf(false) }
    var targetLocated by remember(targetMessage, targetRequest) { mutableStateOf(false) }
    LaunchedEffect(targetMessage, targetRequest) {
        if (targetMessage != null) { query = ""; following = false; kotlinx.coroutines.delay(10_000); targetWaited = true }
    }
    LaunchedEffect(targetMessage, targetRequest, visible.map { dev.forgesworn.kithmoot.session.refOf(it.first.original) }) {
        if (targetMessage != null && !targetLocated) {
            val index = visible.indexOfFirst { dev.forgesworn.kithmoot.session.refOf(it.first.original) == targetMessage }
            if (index >= 0) { following = false; listState.scrollToItem(index); targetLocated = true }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) following = !listState.canScrollForward
        }
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(visible.size, shownPending.size, conversation.lastOrNull()?.id, query) {
        val latest = conversation.lastOrNull()
        if (targetMessage == null && query.isBlank() && latest != null && visible.isNotEmpty()) {
            if (latest.id != lastMessageId && latest.participant == selfParticipant && lastMessageId != null) following = true
            if (following) listState.scrollToItem(lastIndex)
            lastMessageId = latest.id
        } else if (targetMessage == null && query.isBlank() && shownPending.isNotEmpty() && following) listState.scrollToItem(lastIndex)
    }
    LaunchedEffect(latestRequest) {
        if (latestRequest > 0 && query.isBlank() && (visible.isNotEmpty() || shownPending.isNotEmpty())) {
            following = true
            listState.scrollToItem(lastIndex)
        }
    }
    fun send() {
        if (canSend && !sending && !mediaBusy && (draft.text.isNotBlank() || attachments.isNotEmpty() || artwork.isNotEmpty())) {
            val submitted = draft.text
            onSend(submitted) { if (draft.text == submitted) draft = TextFieldValue("") }
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth().imePadding()) {
    val compactArtworkSearch = emojiOpen && artworkSearchOpen && maxHeight < 300.dp
    val trayHeight = if (compactArtworkSearch) maxHeight else (maxHeight * 0.48f).coerceIn(160.dp, 320.dp)
    Column(Modifier.fillMaxSize()) {
        if (targetMessage != null) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (targetLocated) "Message opened from workspace" else if (targetWaited)
                "The message is not in the history loaded here." else "Loading the originating message…",
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClearTarget) { Text("Done") }
        }
        if (privateInvitations.isNotEmpty() && !compactArtworkSearch) {
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
        if (showTitle && !compactArtworkSearch) Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Chat", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = { localSearchOpen = !localSearchOpen }) { Icon(Icons.Filled.Search, "Search messages") }
        }
        if (!compactArtworkSearch) Row(Modifier.fillMaxWidth().clickable(onClick = { privacyOpen = true }).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(if (!internetAllowed) "Nearby Bluetooth · delivery unconfirmed" else privacyLine(lane, torOnly, quiet),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "Message privacy. " + (privacyMeaning(lane, torOnly) ?: "Transport unknown.") })
        }
        if (quiet && !quietCanSend) Text(QuietTransport.CANNOT_SEND, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        if (searching && !compactArtworkSearch) {
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
                itemsIndexed(visible, key = { _, row -> refOf(row.first.original).key }) { index, (r, nested) ->
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
                                val messageText = if (r.retracted) "Message retracted" else artworkMessageText(message.body, message.artwork)
                                if (messageText.isNotEmpty()) PackMessageText(messageText, style = MaterialTheme.typography.bodyLarge,
                                    color = if (r.retracted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                if (!r.retracted) message.attachments.forEach { attachment ->
                                    TextButton(onClick = { expandedImage = attachment }, enabled = internetAllowed) {
                                        Text(if (dev.forgesworn.kithmoot.session.recordingPlaybackMime(attachment.type) != null)
                                            "Show recording: ${attachment.name ?: "Recording"}"
                                        else "Open attachment: ${attachment.name ?: "Image"}")
                                    }
                                }
                                if (!r.retracted) message.artwork.forEach { reference ->
                                    val image = resolveCatalogueArtwork(reference)
                                    if (image == null) {
                                        if (message.body != artworkFallback(message.artwork)) Text(artworkFallback(listOf(reference)), style = MaterialTheme.typography.bodySmall)
                                    } else CatalogueThumbnail(image, Modifier.fillMaxWidth().height(200.dp)
                                        .semantics { contentDescription = "${reference.kind}: ${reference.label}" })
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
                    PendingRow(kept, internetAllowed = internetAllowed, canSend = canSend, onRetry = onRetryPending, doomed = doomed,
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
        if (emojiOpen) ArtworkTray(
            onClose = { emojiOpen = false }, memberPackAvailable = memberPackAvailable, unlockMemberPacks = unlockMemberPacks,
            skinTone = emojiSkinTone, onSkinTone = { emojiSkinTone = it },
            chooseEmoji = { emoji ->
                val start = draft.selection.min; val end = draft.selection.max
                val text = draft.text.replaceRange(start, end, emoji)
                if (text.length <= MAX_CHAT_TEXT_LENGTH) draft = TextFieldValue(text, TextRange(start + emoji.length))
            }, chooseMedia = { image -> onAddArtwork(catalogueArtwork(image)); emojiOpen = false },
            modifier = Modifier.fillMaxWidth().height(trayHeight), initialTab = artworkStartTab,
            mediaEnabled = canSend && artwork.size < MAX_CHAT_ARTWORK,
            compactSearch = compactArtworkSearch, onSearchChanged = { artworkSearchOpen = it },
        )
        var sharingRecording by rememberSaveable { mutableStateOf<String?>(null) }
        val chosenRecording = recordingDrafts.firstOrNull { it.id == sharingRecording }
        if (chosenRecording != null && chosenRecording.uploaded == null) {
            var storage by rememberSaveable(chosenRecording.id) { mutableStateOf(chosenRecording.storageOrigin.orEmpty()) }
            var consent by rememberSaveable(chosenRecording.id, storage) { mutableStateOf(false) }
            val canonical = runCatching { mediaStorageOrigin(storage) }.getOrNull()
            val choice = recordingStorageChoice?.takeIf { it.draft == chosenRecording.id &&
                it.room == chosenRecording.origin.room && it.origin == canonical }
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            AlertDialog(onDismissRequest = { if (!mediaBusy) sharingRecording = null },
                title = { Text("Upload recording privately") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Choose your HTTPS storage server. Only the encrypted file is uploaded; sending a chat message is a separate action.")
                    OutlinedTextField(storage, { storage = it }, label = { Text("Recording storage server") },
                        enabled = !mediaBusy && chosenRecording.storageOrigin == null, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    choice?.let {
                        Text("Authorise this public storage key on your private node before Upload. It cannot access room messages or decrypt recordings.")
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(it.publicKey) }
                        TextButton(onClick = { clipboard.setText(AnnotatedString(it.publicKey)) }) { Text("Copy storage key") }
                        Text("KithMoot keeps this storage identity to retry server deletion after you forget the room. Cleanup requires this device to run and reach the server.")
                        Row(Modifier.fillMaxWidth().toggleable(value = consent, enabled = !mediaBusy,
                            role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = { consent = it }),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(consent, onCheckedChange = null, enabled = !mediaBusy)
                            Text("Allow this encrypted recording to be uploaded to ${it.origin}", Modifier.weight(1f))
                        }
                    }
                    sendError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                } },
                confirmButton = {
                    if (choice == null) TextButton(enabled = !mediaBusy && storage.isNotBlank(),
                        onClick = { onPrepareRecordingStorage(chosenRecording.id, storage) }) { Text("Get storage key") }
                    else TextButton(enabled = !mediaBusy && consent && internetAllowed && !torOnly && canSend,
                        onClick = { onUploadRecordingDraft(chosenRecording.id, choice.origin, consent) }) { Text(if (mediaBusy) "Uploading…" else "Upload") }
                }, dismissButton = {
                    if (recordingUploadRunning) TextButton(onClick = onCancelRecordingUpload) { Text("Cancel upload") }
                    else TextButton(enabled = !mediaBusy, onClick = { sharingRecording = null }) { Text("Cancel") }
                })
        }
        recordingDrafts.forEach { recording ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text("Recording draft: ${recording.sealed.name}. " + when {
                    recording.preparedSend != null -> "Send started. A retry uses the same message."
                    recording.uploaded == null -> "Not uploaded."
                    else -> "Uploaded privately. Not sent."
                }, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                FlowRow {
                    if (recording.uploaded == null) TextButton(enabled = !mediaBusy && internetAllowed && !torOnly && canSend,
                        onClick = { sharingRecording = recording.id }) { Text("Upload recording") }
                    if (recording.uploaded != null) TextButton(enabled = !mediaBusy && canSend,
                        onClick = { onSendRecordingDraft(recording.id) }) { Text(if (recording.preparedSend == null) "Send recording" else "Retry Send") }
                    if (recording.preparedSend == null) TextButton(enabled = !mediaBusy,
                        onClick = { onRemoveRecordingDraft(recording.id) }) { Text("Remove draft") }
                }
            }
        }
        MediaComposer(canSend && !torOnly && internetAllowed, mediaBusy, attachments, onAddImage, onRemoveAttachment,
            showFiles = mediaOpen, showControls = !compactArtworkSearch, artworkEnabled = canSend && artwork.size < MAX_CHAT_ARTWORK, onOpenArtwork = { inputFocus.clearFocus(); inputKeyboard?.hide(); artworkStartTab = ArtworkTab.STICKERS; emojiOpen = true })
        if (!compactArtworkSearch && artwork.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            artwork.forEachIndexed { index, reference ->
                InputChip(selected = true, onClick = { onRemoveArtwork(index) }, label = { Text("${reference.label} ×") },
                    modifier = Modifier.semantics { contentDescription = "Remove ${reference.label} from message" },
                    avatar = { resolveCatalogueArtwork(reference)?.let { CatalogueThumbnail(it, Modifier.size(32.dp)) } })
            }
        }
        if (!compactArtworkSearch) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = draft, onValueChange = { if (it.text.length <= MAX_CHAT_TEXT_LENGTH) draft = it }, modifier = Modifier.weight(1f), enabled = canSend,
                shape = RoundedCornerShape(24.dp),
                leadingIcon = { IconButton(onClick = { inputFocus.clearFocus(); inputKeyboard?.hide(); artworkStartTab = ArtworkTab.EMOJI; emojiOpen = !emojiOpen }, enabled = canSend) { Icon(Icons.Filled.EmojiEmotions, "Emoji") } },
                trailingIcon = { IconButton(onClick = { mediaOpen = !mediaOpen }, enabled = canSend && !torOnly && internetAllowed) { Icon(Icons.Filled.AttachFile, "Images, GIFs and stickers") } },
                placeholder = { Text("Say something") }, maxLines = 4, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
            IconButton(onClick = { send() }, enabled = canSend && (draft.text.isNotBlank() || attachments.isNotEmpty() || artwork.isNotEmpty()) && !sending && !mediaBusy, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
        sendError?.let { Text(it, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
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
        if (internetAllowed && messages.any { it.attachments.contains(attachment) } && resolved.stream.flatMap { listOf(it) + it.replies }.any { !it.retracted && it.shown.attachments.contains(attachment) }) {
            AttachmentViewer(attachment, onClose = { expandedImage = null })
        } else LaunchedEffect(attachment) { expandedImage = null }
    }
    profileTarget?.let { target ->
        ParticipantDetails(target.participant, target.name, if (profilesEnabled) profiles[target.participant] else null,
            onClose = { profileTarget = null },
            onMessage = if (target.participant in privateConversationPeers) ({ profileTarget = null; onMessagePrivately(target.participant) }) else null)
    }
    if (privacyOpen) AlertDialog(onDismissRequest = { privacyOpen = false }, title = { Text("Message privacy") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Messages are encrypted to the room.")
            Text(if (!internetAllowed) "This room uses nearby Bluetooth. Messages remain encrypted to the room; handing bytes to Bluetooth does not confirm that another member received them."
                else privacyMeaning(lane, torOnly) ?: "The transport for the next message is not yet known.")
            if (quiet) Text(QuietTransport.MEANING)
            Text("Public profiles are optional. Lookups share participant keys with room relays; picture hosts see image requests. Names and pictures are self-reported.")
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(profilesEnabled, onProfilesEnabled, enabled = internetAllowed); Text("Show public profiles") }
        } }, confirmButton = { TextButton(onClick = { privacyOpen = false }) { Text("Done") } })
    moreReactionTarget?.let { target ->
        if (!canSend || resolved.byKey[MessageRef(target.id, target.participant).key]?.retracted == true) {
            LaunchedEffect(target) { moreReactionTarget = null }
        } else EmojiDialog(onDismiss = { moreReactionTarget = null }, memberPackAvailable, unlockMemberPacks, emojiSkinTone, { emojiSkinTone = it }) { emoji ->
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
                    REACTION_EMOJIS.forEach { baseEmoji ->
                        val emoji = emojiForSkinTone(baseEmoji, emojiSkinTone)
                        val active = reactionUpdates(messages, target).filter { it.reaction!!.emoji == emoji && it.reaction.active }
                        val selected = active.any { it.participant == selfParticipant }
                        TextButton(onClick = { onReact(target, emoji); reactionTarget = null },
                            modifier = Modifier.semantics { contentDescription = "${if (selected) "Remove" else "Add"} $emoji reaction, ${active.size}" }) { PackEmoji(emoji, Modifier.size(28.dp)) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiDialog(onDismiss: () -> Unit, memberPackAvailable: () -> Boolean, unlockMemberPacks: suspend () -> Boolean, skinTone: Int, onSkinTone: (Int) -> Unit, choose: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
            ArtworkTray(onDismiss, memberPackAvailable, unlockMemberPacks, skinTone, onSkinTone, choose, {},
                Modifier.fillMaxWidth().height(maxHeight), reactionsOnly = true, compactSearch = maxHeight < 250.dp)
        }
    }
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
internal fun pendingStatus(state: PendingChatState, internetAllowed: Boolean = true): String = if (!internetAllowed && state == PendingChatState.UNKNOWN)
    "Offered over nearby Bluetooth. Delivery is not confirmed; retry keeps the same message."
    else if (!internetAllowed && state == PendingChatState.REFUSED) "Not offered to Bluetooth. Retry when a nearby link is available."
    else when (state) {
    PendingChatState.WAITING -> "Pending: will send when you are connected."
    PendingChatState.SENDING -> "Sending…"
    PendingChatState.REFUSED -> "Not sent: the relays turned it down. Trying again shortly."
    PendingChatState.UNKNOWN -> "Not confirmed: no relay answered in time. Trying again shortly."
    PendingChatState.MOVED -> "Not sent: this conversation changed its key before it went. Copy it into the conversation to send it."
}

/** A message the person wrote that no relay has confirmed, at the end of the chat in the shape of their own. */
@Composable
private fun PendingRow(kept: PendingChat, internetAllowed: Boolean = true, canSend: Boolean, onRetry: () -> Unit, doomed: Boolean = false, onEdit: () -> Unit, onDelete: () -> Unit, onRemove: () -> Unit) {
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
        Text(if (doomed) dev.forgesworn.kithmoot.session.WILL_NOT_BE_SENT else pendingStatus(kept.state, internetAllowed), Modifier.widthIn(max = 320.dp).padding(horizontal = 4.dp, vertical = 2.dp),
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
