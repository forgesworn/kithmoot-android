package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.Icons
import kotlinx.coroutines.launch
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import dev.forgesworn.kithmoot.ui.theme.kithMoot
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
    /** Sends the text as a reply to a message, as received, then calls back once it is kept; null offers no Reply. */
    onReply: ((String, ChatMessage, () -> Unit) -> Unit)? = null,
    onOpenPrivateConversation: (ChatMessage) -> Unit = {},
    /** The name of a room this phone has saved, by room id: for the sender's own invitation card. */
    savedRoomName: (String) -> String? = { null },
    memberPackAvailable: () -> Boolean = { false },
    unlockMemberPacks: suspend () -> Boolean = { false },
    attachments: List<ChatAttachment> = emptyList(),
    recordingDrafts: List<dev.forgesworn.kithmoot.media.recording.RecordingShareDraft> = emptyList(),
    recordingHandoffStates: Map<String, dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState> = emptyMap(),
    onRetryRecordingWithdrawal: (String) -> Unit = {},
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
    recordingScheduleBusy: Boolean = false,
    /** Where files go, the one-time notice and the uploads-off line (kithmoot #527). */
    files: ComposerFiles = ComposerFiles(),
    onRemoveAttachment: (String) -> Unit = {},
    privateConversationPeers: List<String> = emptyList(),
    onMessagePrivately: (String) -> Unit = {},
    /** People here who may be invited to another room, and how: see [dev.forgesworn.kithmoot.ui.RoomViewModel.openRoomInvite]. */
    roomInvitePeers: List<String> = emptyList(),
    onInviteToRoom: (String) -> Unit = {},
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
    /** Participants who say they are agents: their name carries an outlined "Agent" label. */
    agentParticipants: Set<String> = emptySet(),
    targetMessage: dev.forgesworn.kithmoot.session.MessageRef? = null,
    targetRequest: Int = 0,
    onClearTarget: () -> Unit = {},
) {
    var expandedImage by remember { mutableStateOf<ChatAttachment?>(null) }
    var draft by rememberSaveable(stateSaver = boundedDraftSaver) { mutableStateOf(TextFieldValue("")) }
    // Counts deliberate changes: typing, emoji, a restored pending message. A
    // change while a send holds the field (a keyboard commit landing as Send is
    // pressed) is not counted, so it cannot leave the sent text in the box and
    // invite a second Send; typing after the hold ends is never erased.
    var draftReplacements by remember { mutableIntStateOf(0) }
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
    // The message the next send answers, by its key in the conversation.
    var replyKey by rememberSaveable { mutableStateOf<String?>(null) }
    var replyFocusRequest by remember { mutableIntStateOf(0) }
    val inputFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    // A followed quote: where to go, and the message marked for a moment once there.
    var jumpTo by remember { mutableStateOf<MessageRef?>(null) }
    var jumpRequest by remember { mutableIntStateOf(0) }
    var jumpMarked by remember { mutableStateOf<String?>(null) }
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
    // a retracted one shown as such, and every message in time order, a reply
    // among them with a quote of the message it answers. See Messages.kt and
    // docs/messages.md in the reference implementation.
    val resolved = resolveConversation(messages)
    // A reply's target that has gone (retracted, or another conversation) is let go.
    val replyTarget = replyKey?.let { resolved.byKey[it] }?.takeIf { !it.retracted }
    LaunchedEffect(replyKey, replyTarget == null) { if (replyKey != null && replyTarget == null) replyKey = null }
    val privateInvitations = messages.asReversed().filter { message ->
        val invite = message.invite
        invite != null && (message.participant == selfParticipant || invite.to == selfParticipant)
    }.distinctBy { it.invite!!.room }.take(5).reversed()
    /** [participant]'s name as this chat knows it: their profile's, else the one they last gave here. */
    fun nameIn(participant: String): String? = profiles[participant]?.name?.takeIf { it.isNotBlank() }
        ?: messages.lastOrNull { it.participant == participant && !it.name.isNullOrBlank() }?.name
    /** Who wrote the message a quote names: "You", the name it was sent under, else the one this chat knows. */
    fun quoteWho(quote: ReplyQuote): String {
        if (quote.ref.participant.equals(selfParticipant, ignoreCase = true)) return "You"
        val sentAs = when (quote) {
            is ReplyQuote.Ready -> quote.target.original.name
            is ReplyQuote.Deleted -> quote.target.original.name
            is ReplyQuote.Missing -> null
        }
        return sentAs?.takeIf { it.isNotBlank() } ?: nameIn(quote.ref.participant) ?: NAMELESS
    }
    val rows = resolved.byKey.values.sortedWith(compareBy(compareMessages) { it.original })
    val conversation = rows.map { it.shown }
    val visible = rows.filter { r ->
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
    // A note follows the last message sent no later than it; notes from
    // before the first message lead. Hidden while searching, which looks for
    // what people said.
    val shownNotes = if (query.isBlank()) notes.sortedWith(compareBy({ it.sentAt }, { it.id })) else emptyList()
    val notesAfter = mutableMapOf<Int, MutableList<dev.forgesworn.kithmoot.ui.RoomNote>>()
    val leadingNotes = mutableListOf<dev.forgesworn.kithmoot.ui.RoomNote>()
    for (note in shownNotes) {
        val after = visible.indexOfLast { it.shown.sentAt <= note.sentAt }
        if (after < 0) leadingNotes += note else notesAfter.getOrPut(after) { mutableListOf() } += note
    }
    fun noteText(note: dev.forgesworn.kithmoot.ui.RoomNote): String {
        val who = if (note.participant == selfParticipant) "You" else
            messages.lastOrNull { it.participant == note.participant && it.name != null }?.name
                ?: profiles[note.participant]?.name ?: NAMELESS
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
    LaunchedEffect(targetMessage, targetRequest, visible.map { dev.forgesworn.kithmoot.session.refOf(it.original) }) {
        if (targetMessage != null && !targetLocated) {
            val index = visible.indexOfFirst { dev.forgesworn.kithmoot.session.refOf(it.original) == targetMessage }
            if (index >= 0) { following = false; listState.scrollToItem(index); targetLocated = true }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) following = !listState.canScrollForward
        }
    }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // Following a quote: the original about a third of the way down, smoothly
    // unless animations are off, marked for two seconds. The latest button
    // then shows the way back.
    LaunchedEffect(jumpTo, jumpRequest) {
        val ref = jumpTo ?: return@LaunchedEffect
        val index = visible.indexOfFirst { refOf(it.original) == ref }
        if (index >= 0) {
            following = false
            val offset = -(listState.layoutInfo.viewportSize.height / 3)
            val still = android.provider.Settings.Global.getFloat(context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
            if (still) listState.scrollToItem(index, offset) else listState.animateScrollToItem(index, offset)
            jumpMarked = ref.key
            kotlinx.coroutines.delay(2_000)
            if (jumpMarked == ref.key) jumpMarked = null
        }
        jumpTo = null
    }
    LaunchedEffect(replyFocusRequest) {
        if (replyFocusRequest > 0) { runCatching { inputFocusRequester.requestFocus() }; inputKeyboard?.show() }
    }
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
    var choosingFiles by rememberSaveable { mutableStateOf(false) }
    fun send() {
        if (canSend && !sending && !mediaBusy && (draft.text.isNotBlank() || attachments.isNotEmpty() || artwork.isNotEmpty())) {
            val submitted = draft.text
            val replacements = draftReplacements
            val clear = { if (draft.text == submitted || draftReplacements == replacements) draft = TextFieldValue("") }
            val answering = replyTarget
            val answeringKey = replyKey
            if (answering != null && onReply != null) onReply(submitted, answering.original) {
                clear()
                if (replyKey == answeringKey) replyKey = null
            } else onSend(submitted, clear)
        }
    }
    // The one-time notice, for a picked image or a long paste waiting at Send; hidden while the choices are open over it.
    files.notice?.takeIf { !choosingFiles }?.let { notice ->
        FileNoticeDialog(notice, files.choice.retired,
            action = if (notice.image != null) "Upload to ${dev.forgesworn.kithmoot.session.fileHost(notice.origin)}" else "Send",
            onAccept = { files.onAcceptNotice(); if (notice.image == null) send() },
            onChooseAnother = { choosingFiles = true }, onDismiss = files.onDismissNotice)
    }
    if (choosingFiles) WhereFilesGoDialog(files.choice, files.actions, onDone = { choosingFiles = false })
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
                    val sentByMe = message.participant == selfParticipant
                    // Names only, never a key (design-calm-shell C7): the
                    // sender's own, else their profile's, else Somebody.
                    val card = dev.forgesworn.kithmoot.ui.inviteCard(message.body, sentByMe,
                        sender = message.name?.takeIf { it.isNotBlank() } ?: nameIn(message.participant),
                        recipient = nameIn(invite.to), room = if (sentByMe) savedRoomName(invite.room) else null,
                        time = messageTime(message.sentAt))
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                                Text(card.title, style = MaterialTheme.typography.titleSmall)
                                Text(card.detail, style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = { onOpenPrivateConversation(message) }, Modifier.heightIn(min = 48.dp)) { Text(card.action) }
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
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Inside the first row rather than an item of their own, so a
                // row's index stays its item index for the scrolling below.
                if (visible.isEmpty() && leadingNotes.isNotEmpty()) item(key = "room-notes-leading") { Column { leadingNotes.forEach { NoteLine(it) } } }
                itemsIndexed(visible, key = { _, row -> refOf(row.original).key }) { index, r ->
                    if (index == 0) leadingNotes.forEach { NoteLine(it) }
                    val message = r.shown
                    val mine = message.participant == selfParticipant
                    val addressed = !r.retracted && mentionedBy(message, selfParticipant)
                    val previous = visible.getOrNull(index - 1)?.shown
                    // A run is one sender within five minutes on one day: one name, closer gaps, the avatar at its end.
                    val senderHeader = previous == null || !joinsRun(previous, message)
                    val nextShown = visible.getOrNull(index + 1)?.shown
                    val runEnd = nextShown == null || !joinsRun(message, nextShown)
                    val tokens = MaterialTheme.kithMoot
                    val bubbleInk = if (mine) tokens.onMine else MaterialTheme.colorScheme.onSurface
                    val metaInk = if (mine) tokens.onMine.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                    if (index == 0 || messageDate(visible[index - 1].shown.sentAt) != messageDate(message.sentAt)) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(messageDate(message.sentAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(top = if (senderHeader) 10.dp else 0.dp),
                        horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = if (mine) Arrangement.End else Arrangement.spacedBy(8.dp)) {
                        if (!mine) {
                            // The avatar sits at the bottom of the run; the rest of it keeps the column.
                            if (runEnd) ProfileAvatar(message.participant, message.name, profiles[message.participant],
                                Modifier.size(32.dp).clearAndSetSemantics { })
                            else Spacer(Modifier.size(32.dp))
                        }
                        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start, modifier = Modifier.weight(1f, fill = false)) {
                        if (!mine && senderHeader) Row(modifier = Modifier.heightIn(min = 44.dp).clickable(
                            onClickLabel = "View participant details", onClick = { profileTarget = message }), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(message.name ?: profiles[message.participant]?.name ?: NAMELESS,
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (message.participant in agentParticipants) AgentLabel()
                        }
                        val marked = jumpMarked == refOf(r.original).key
                        val actionsLabel = if (onReply != null && canSend) "Reply, react and more" else null
                        val markInk = MaterialTheme.colorScheme.primary
                        Surface(shape = RoundedCornerShape(12.dp),
                            color = if (mine) tokens.mine else tokens.bubble, contentColor = bubbleInk,
                            border = if (tokens.highContrast) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
                            // A followed quote's original is ringed just outside its bubble, clear of
                            // its edge, so the ring shows whatever colour the bubble is.
                            modifier = Modifier.widthIn(max = 320.dp).drawWithContent {
                                drawContent()
                                if (marked) {
                                    val gap = 4.dp.toPx(); val stroke = 3.dp.toPx()
                                    drawRoundRect(markInk, topLeft = androidx.compose.ui.geometry.Offset(-gap, -gap),
                                        size = androidx.compose.ui.geometry.Size(size.width + 2 * gap, size.height + 2 * gap),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx() + gap),
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                                }
                            }.combinedClickable(
                                onClick = { reactionTarget = r.original }, onClickLabel = actionsLabel ?: "Message details and reactions",
                                onLongClick = { reactionTarget = r.original }, onLongClickLabel = actionsLabel ?: "React to message",
                            )) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                // The message this one answers, before its words, so it is heard first too.
                                val quote = if (r.retracted) null else replyQuoteOf(r, resolved.byKey)
                                if (quote != null) {
                                    val who = quoteWho(quote)
                                    ReplyQuoteBlock(who, quote.words(), inMine = mine,
                                        go = if (quote is ReplyQuote.Ready && quote.snippet.isNotEmpty()) ({
                                            query = ""; jumpTo = quote.ref; jumpRequest++
                                        }) else null,
                                        goLabel = quoteGoLabel(who, who == "You", quote.words()),
                                        onHold = { reactionTarget = r.original }, holdLabel = actionsLabel ?: "React to message")
                                }
                                if (addressed) Text("Mentioned you", style = MaterialTheme.typography.labelMedium, color = if (mine) tokens.onMine else MaterialTheme.colorScheme.primary)
                                // A link takes its own tap; anywhere else the bubble's tap and hold still open reactions.
                                val messageText = if (r.retracted) "Message retracted" else artworkMessageText(message.body, message.artwork)
                                if (messageText.isNotEmpty()) PackMessageText(messageText, style = MaterialTheme.typography.bodyLarge,
                                    color = if (r.retracted) metaInk else bubbleInk, linkColor = if (mine) tokens.onMine else MaterialTheme.colorScheme.primary)
                                if (!r.retracted) message.attachments.forEach { attachment ->
                                    TextButton(onClick = { expandedImage = attachment }, enabled = internetAllowed) {
                                        Text(if (dev.forgesworn.kithmoot.session.recordingPlaybackMime(attachment.type) != null)
                                            "Show recording: ${attachment.name ?: "Recording"}"
                                        else if (isTextDocumentType(attachment.type)) "Open document: ${attachment.name ?: "Text"}"
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
                                    if (r.edited && !r.retracted) "edited" else null)
                                Text(meta.joinToString(" · "), Modifier.align(Alignment.End).padding(top = 3.dp), style = MaterialTheme.typography.labelMedium,
                                    color = metaInk)
                            }
                        }
                        }
                      }
                        if (!r.retracted) {
                            val updates = reactionUpdates(messages, r.original)
                            FlowRow(modifier = Modifier.padding(start = if (mine) 0.dp else 40.dp, top = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                        onEdit = { if (draft.text.isBlank()) onEditPending(kept.id) { draftReplacements++; draft = TextFieldValue(it, TextRange(it.length)) } else editPendingId = kept.id },
                        onDelete = { deletePendingId = kept.id }, onRemove = { removePendingId = kept.id })
                }
            }
            // Drawn after the list, so it sits above it. Under the empty list
            // filling the box, the text was missing from the accessibility
            // tree that UI Automator reads.
            if (visible.isEmpty() && shownNotes.isEmpty() && shownPending.isEmpty() && !(LocalEmptyRoomShown.current && query.isBlank())) Text(emptyChat(query, torOnly, relaysUp), Modifier.align(Alignment.Center).padding(20.dp))
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
                draftReplacements++
                draft = TextFieldValue(text, TextRange(start + emoji.length))
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
            val canonical = runCatching { chosenMediaStorage(storage) }.getOrNull()
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
            val custody = recordingHandoffStates[recording.id]
            val cancellationPending = custody == dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.CANCEL_PENDING
            val cancelled = custody == dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.WITHDRAWN
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text("Recording draft: ${recording.sealed.name}. " + when {
                    cancellationPending -> "Cancellation unconfirmed. Your recording is retained."
                    cancelled -> "Sending cancelled. Your recording is retained."
                    recording.preparedSend != null -> "Send started. A retry uses the same message."
                    recording.uploaded == null -> "Not uploaded."
                    else -> "Uploaded privately. Not sent."
                }, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                FlowRow {
                    if (recording.uploaded == null) TextButton(enabled = !mediaBusy && internetAllowed && !torOnly && canSend,
                        onClick = { sharingRecording = recording.id }) { Text("Upload recording") }
                    if (cancellationPending) TextButton(enabled = !mediaBusy && !recordingScheduleBusy,
                        onClick = { onRetryRecordingWithdrawal(recording.id) }) { Text("Retry cancellation") }
                    if (recording.uploaded != null && !cancellationPending && !cancelled) TextButton(enabled = !mediaBusy && !recordingScheduleBusy && canSend,
                        onClick = { onSendRecordingDraft(recording.id) }) { Text(if (recording.preparedSend == null) "Send recording" else "Retry Send") }
                    if (recording.preparedSend == null) TextButton(enabled = !mediaBusy,
                        onClick = { onRemoveRecordingDraft(recording.id) }) { Text("Remove draft") }
                }
            }
        }
        files.off?.let { FilesOffLine(it, onChoose = { choosingFiles = true }, onDismiss = files.onDismissOff) }
        MediaComposer(canSend && !torOnly && internetAllowed, mediaBusy, attachments, files.onPick, onRemoveAttachment,
            showFiles = mediaOpen, showControls = !compactArtworkSearch, artworkEnabled = canSend && artwork.size < MAX_CHAT_ARTWORK, onOpenArtwork = { inputFocus.clearFocus(); inputKeyboard?.hide(); artworkStartTab = ArtworkTab.STICKERS; emojiOpen = true })
        if (!compactArtworkSearch && artwork.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            artwork.forEachIndexed { index, reference ->
                InputChip(selected = true, onClick = { onRemoveArtwork(index) }, label = { Text("${reference.label} ×") },
                    modifier = Modifier.semantics { contentDescription = "Remove ${reference.label} from message" },
                    avatar = { resolveCatalogueArtwork(reference)?.let { CatalogueThumbnail(it, Modifier.size(32.dp)) } })
            }
        }
        if (!compactArtworkSearch) {
            val canAddFile = canSend && !torOnly && internetAllowed
            val canPost = canSend && (draft.text.isNotBlank() || attachments.isNotEmpty() || artwork.isNotEmpty()) && !sending && !mediaBusy
            replyTarget?.let { target ->
                val mineToo = target.original.participant.equals(selfParticipant, ignoreCase = true)
                val who = if (mineToo) "yourself" else target.original.name?.takeIf { it.isNotBlank() } ?: nameIn(target.original.participant) ?: NAMELESS
                ReplyingBar("Replying to $who", quoteSnippet(target.shown).ifEmpty { target.shown.body }, onCancel = { replyKey = null })
            }
            // One row, one centre line: Add a file, the field (Emoji inside it, at the right), Send. Every
            // control is 48 dp and the row centres what it holds, so at any text size they share a centre.
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { mediaOpen = !mediaOpen }, enabled = canAddFile, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Add, "Add a file") }
                Row(Modifier.weight(1f).heightIn(min = 48.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp)),
                    verticalAlignment = Alignment.CenterVertically) {
                    // Held while the message is being kept or confirmed: the text Send took stays as it was sent.
                    androidx.compose.foundation.text.BasicTextField(value = draft, onValueChange = { if (!sending && it.text != draft.text) draftReplacements++; draft = it },
                        modifier = Modifier.weight(1f).padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                            .focusRequester(inputFocusRequester), enabled = canSend, readOnly = sending,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 6, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                        decorationBox = { field ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (draft.text.isEmpty()) Text("Say something", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                field()
                            }
                        })
                    IconButton(onClick = { inputFocus.clearFocus(); inputKeyboard?.hide(); artworkStartTab = ArtworkTab.EMOJI; emojiOpen = !emojiOpen },
                        enabled = canSend, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.EmojiEmotions, "Emoji") }
                }
                FilledIconButton(onClick = { send() }, enabled = canPost, modifier = Modifier.size(48.dp)) {
                    // A greyed arrow read as "nothing happened", so people pressed again: show the send is under way.
                    // Static on purpose: an endless spinner keeps Compose from idling through a long upload.
                    if (sending) Icon(Icons.Filled.HourglassTop, "Sending")
                    else Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
        // Kept messages release the composer at once; only an upload or a wait for a relay lasts long enough to explain.
        var slowSend by remember { mutableStateOf(false) }
        LaunchedEffect(sending) { slowSend = false; if (sending) { kotlinx.coroutines.delay(1_000); slowSend = true } }
        if (sending && slowSend) Text("Sending… no need to press Send again.",
            Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
        longChatPasteNote(draft.text)?.let { Text(it, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall) }
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
            confirmButton = { TextButton(onClick = { editPendingId = null; onEditPending(id) { draftReplacements++; draft = TextFieldValue(it, TextRange(it.length)) } }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { editPendingId = null }) { Text("Keep typing") } })
    }
    expandedImage?.let { attachment ->
        if (internetAllowed && messages.any { it.attachments.contains(attachment) } && resolved.byKey.values.any { !it.retracted && it.shown.attachments.contains(attachment) }) {
            AttachmentViewer(attachment, onClose = { expandedImage = null })
        } else LaunchedEffect(attachment) { expandedImage = null }
    }
    profileTarget?.let { target ->
        ParticipantDetails(target.participant, target.name, if (profilesEnabled) profiles[target.participant] else null,
            onClose = { profileTarget = null },
            onMessage = if (target.participant in privateConversationPeers) ({ profileTarget = null; onMessagePrivately(target.participant) }) else null,
            onInvite = if (target.participant in roomInvitePeers) ({ profileTarget = null; onInviteToRoom(target.participant) }) else null)
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
        val targetKey = resolved.byKey.entries.firstOrNull { it.value.original == target }?.key
        val resolvedTarget = targetKey?.let { resolved.byKey[it] }
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
        }, dismissButton = { Row {
            if (onReply != null && canSend && targetKey != null && resolvedTarget?.retracted != true) TextButton(onClick = {
                replyKey = targetKey; reactionTarget = null; replyFocusRequest++
            }) { Text("Reply") }
            if (bodyText != null) TextButton(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(bodyText))
                // Android 13+ shows its own clipboard confirmation toast; a
                // second one here would just be noise.
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                    android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
                }
                reactionTarget = null
            }) { Text("Copy text") }
        } }, confirmButton = { TextButton(onClick = { reactionTarget = null }) { Text("Done") } })
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

/** [next] continues [first]'s run: the same sender, within five minutes, on the same day. */
private fun joinsRun(first: ChatMessage, next: ChatMessage): Boolean =
    first.participant == next.participant && next.sentAt - first.sentAt <= 300 && messageDate(first.sentAt) == messageDate(next.sentAt)

/**
 * "Agent" beside a name: the word in an outlined pill, so it reads without
 * colour, and in forced colours and high contrast as well. TalkBack says "agent".
 */
@Composable
internal fun AgentLabel(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.kithMoot.agent
    Text("Agent", modifier.border(1.dp, ink, androidx.compose.foundation.shape.CircleShape)
        .padding(horizontal = 8.dp, vertical = 1.dp).clearAndSetSemantics { contentDescription = "agent" },
        style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 1)
}

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
    val tokens = MaterialTheme.kithMoot
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalAlignment = Alignment.End) {
        // Mine, with a boundary: the one bubble that no relay has confirmed.
        Surface(shape = RoundedCornerShape(12.dp), color = tokens.mine, contentColor = tokens.onMine,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.widthIn(max = 320.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(kept.text.ifEmpty { "Message kept on this phone" }, style = MaterialTheme.typography.bodyLarge, color = tokens.onMine)
                Text(messageClock(kept.sentAt), Modifier.align(Alignment.End).padding(top = 3.dp), style = MaterialTheme.typography.labelMedium,
                    color = tokens.onMine.copy(alpha = 0.85f))
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

/**
 * The quote at the top of a reply's bubble: an accent bar, who wrote the
 * message it answers and one line of what it says now. With [go] it is a
 * button to the original; without, a note. Holding it does what holding
 * the bubble does.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReplyQuoteBlock(who: String, words: String, inMine: Boolean, go: (() -> Unit)?, goLabel: String,
    onHold: () -> Unit, holdLabel: String) {
    val tokens = MaterialTheme.kithMoot
    val ink = if (inMine) tokens.onMine else MaterialTheme.colorScheme.onSurface
    val accent = if (inMine) tokens.onMine else MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(8.dp)
    val surface = if (tokens.highContrast) Modifier.border(1.dp, ink, shape) else Modifier.background(ink.copy(alpha = 0.08f), shape)
    val action = if (go == null) Modifier else Modifier
        .combinedClickable(onClick = go, onClickLabel = "Go to message", role = androidx.compose.ui.semantics.Role.Button,
            onLongClick = onHold, onLongClickLabel = holdLabel)
        .semantics { contentDescription = goLabel }
    // The words go unread inside a button, whose name already says them.
    val inner = if (go == null) Modifier else Modifier.clearAndSetSemantics { }
    Row(Modifier.padding(bottom = 6.dp).clip(shape).then(surface).then(action)
        .heightIn(min = if (go == null) 0.dp else 48.dp).height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(who, inner, style = MaterialTheme.typography.labelMedium, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(words, inner, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Above the message box while a reply is being written: what it answers, and a way out. */
@Composable
internal fun ReplyingBar(title: String, words: String, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp).height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp).semantics(mergeDescendants = true) { }) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(words, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Stop replying" }) { Text("Cancel") }
    }
}
