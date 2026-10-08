package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.em
import dev.forgesworn.kithmoot.protocol.conferenceEnded
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.forgesworn.kithmoot.account.AccountRoom
import dev.forgesworn.kithmoot.storage.SavedRoomSummary
import dev.forgesworn.kithmoot.ui.StartState
import dev.forgesworn.kithmoot.ui.qr.QrScanner
import dev.forgesworn.kithmoot.session.ConferenceLength
import dev.forgesworn.kithmoot.ui.theme.LocalTextSizeSetting
import kotlin.math.roundToInt

/**
 * Home: one destination with two states (design-home-rooms.md section 4).
 * Somebody with rooms taps the one that matters; somebody with none starts a
 * room and sends its link.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartScreen(
    state: StartState,
    onRoomNameChanged: (String) -> Unit,
    onJoinUrlChanged: (String) -> Unit,
    onRelaysChanged: (String) -> Unit,
    onAnonymousModeChanged: (Boolean) -> Unit,
    onPersistentGroupChanged: (Boolean) -> Unit,
    onStartRoom: () -> Unit,
    onConferenceLengthChanged: (ConferenceLength) -> Unit = {},
    onRoomDestructChanged: (Boolean) -> Unit = {},
    onJoin: () -> Unit,
    onReopen: (String) -> Unit,
    onForget: (String) -> Unit,
    onProject: (String, String) -> Unit,
    onPin: (String, Boolean) -> Unit = { _, _ -> },
    onPairBothy: (String, String) -> Unit = { _, _ -> },
    onDisconnectBothy: (String) -> Unit = {},
    onRevokeBothyGuests: (String) -> Unit = {},
    onRetryStorage: () -> Unit,
    onResetStorage: () -> Unit,
    modifier: Modifier = Modifier,
    /** Kept for callers that still build it; home no longer shows an account
     *  entry point itself (Q6) - the account lives in Settings and the
     *  sign-in sheet, both hosted above this screen. */
    account: AccountActions = AccountActions.None,
    onAddOfferedCard: () -> Unit = {},
    onDismissCardOffer: () -> Unit = {},
    projects: ProjectActions = ProjectActions(),
    accountRooms: AccountRoomActions = AccountRoomActions(),
    /** The docked call's room: forgetting it from under the call would strand it. */
    callRoomId: String? = null,
    onStopOpening: () -> Unit = {},
    onOpenProjects: () -> Unit = {},
    onAddRoomToProject: (String) -> Unit = {},
    onShareInvite: (String) -> Unit = {},
    onSignIn: () -> Unit = {},
    /** VMLS rooms (P3-03b-3 decision 21), shown beside saved rooms. */
    vmlsRooms: (@Composable () -> Unit)? = null,
    /** Removes one self-destructed room's row. */
    onDismissTombstone: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val is24Hour = remember { android.text.format.DateFormat.is24HourFormat(context) }
    val zone = remember { java.time.ZoneId.systemDefault() }
    val locale = remember { java.util.Locale.getDefault() }
    val prefs = remember { context.getSharedPreferences("kithmoot.display", android.content.Context.MODE_PRIVATE) }

    // Older and Ended start folded; each fold's state is this device's, not the room's.
    val foldOpen = remember { mutableStateMapOf(
        HomeSection.OLDER to prefs.getBoolean(foldPreference(HomeSection.OLDER), false),
        HomeSection.ENDED to prefs.getBoolean(foldPreference(HomeSection.ENDED), false),
    ) }
    var query by rememberSaveable { mutableStateOf("") }
    var projectTab by rememberSaveable { mutableStateOf(prefs.getString("projectTab", "") ?: "") }
    var newRoomOpen by rememberSaveable { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<SavedRoomSummary?>(null) }
    var filing by remember { mutableStateOf<SavedRoomSummary?>(null) }
    var filedAs by remember { mutableStateOf("") }
    var pairingRoom by remember { mutableStateOf<SavedRoomSummary?>(null) }
    var pairingCode by remember { mutableStateOf("") }
    var scanningPairingCode by remember { mutableStateOf(false) }
    var disconnectingRoom by remember { mutableStateOf<SavedRoomSummary?>(null) }
    var revokingRoom by remember { mutableStateOf<SavedRoomSummary?>(null) }
    var removingAccountRoom by remember { mutableStateOf<AccountRoom?>(null) }
    var resetting by remember { mutableStateOf(false) }

    val enabled = !state.busy && !state.loadingRooms && !state.storageError
    val signedIn = state.account != null

    val homeRooms = remember(state.savedRooms, state.roomBookmarks.rooms, signedIn) {
        mergeRooms(state.savedRooms, state.roomBookmarks.rooms, signedIn)
    }
    val returning = isReturning(homeRooms, signedIn, state.destructTombstones.size)
    val sortedIds = remember(homeRooms) { sortByActivity(homeRooms) { null }.map { it.id } }
    var previousOrder by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val listState = rememberLazyListState()
    // The full hold-order rule (design-home-rooms.md section 6) also freezes
    // while a row's menu is open or the list has focus, and releases on
    // TalkBack's own schedule; this covers the scrolling case, the one a
    // finger notices most, and is simplest to build without wiring a shared
    // "any menu open" signal through every row.
    val held = listState.isScrollInProgress
    val orderedIds = holdOrder(previousOrder, sortedIds, held)
    SideEffect { previousOrder = orderedIds }
    val now = System.currentTimeMillis() / 1000
    val freshSections = homeRooms.associate { it.id to sectionOf(it, null, now) }
    var previousSections by remember { mutableStateOf<Map<String, HomeSection>?>(null) }
    val sections = holdSections(previousSections, freshSections, held)
    SideEffect { previousSections = sections }
    val byId = remember(homeRooms) { homeRooms.associateBy { it.id } }
    val orderedRooms = orderedIds.mapNotNull(byId::get)

    val savedById = remember(state.savedRooms) { state.savedRooms.associateBy { it.id } }
    val bookmarksById = remember(state.roomBookmarks.rooms) { state.roomBookmarks.rooms.associateBy { it.roomId } }

    fun openRoom(room: HomeRoom) {
        val bookmark = bookmarksById[room.id]
        if (room.source == RoomSource.ACCOUNT && bookmark != null) accountRooms.open(bookmark) else onReopen(room.id)
    }

    fun actionsFor(room: HomeRoom): List<ConversationAction> = buildList {
        if (room.canShareInvite) add(ConversationAction("Share invite link") { onShareInvite(room.id) })
        val saved = savedById[room.id]
        if (saved != null) {
            add(0, ConversationAction(if (saved.pinned) "Unpin" else "Pin") { onPin(saved.id, !saved.pinned) })
            add(ConversationAction("Add to a project") { onAddRoomToProject(saved.id) })
            add(ConversationAction(if (saved.project != null) "Change local group" else "Group on this phone") { filing = saved; filedAs = saved.project.orEmpty() })
            if (!saved.anonymous && saved.account == state.account?.pubkey) {
                if (saved.id in state.linkConnectedRooms) add(ConversationAction("Disconnect Bothy") { disconnectingRoom = saved })
                else add(ConversationAction("Connect Bothy") { pairingRoom = saved; pairingCode = "" })
            }
            if (!saved.anonymous && saved.id in state.linkGrantOwnerRooms) add(ConversationAction("Revoke guest access", destructive = true) { revokingRoom = saved })
            if (saved.id != callRoomId) add(ConversationAction("Remove from this phone", destructive = true) { forgetting = saved })
        }
        if (room.source == RoomSource.ACCOUNT) bookmarksById[room.id]?.let { bookmark ->
            add(ConversationAction("Remove from account", destructive = true) { removingAccountRoom = bookmark })
        }
    }

    val loadingFirst = state.loadingRooms && state.savedRooms.isEmpty() && !state.storageError

    BoxWithConstraints(modifier.fillMaxSize()) {
        val layout = homeLayout(maxWidth.value.roundToInt(), maxHeight.value.roundToInt())
        when {
            loadingFirst -> LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter)
                .semantics { contentDescription = "Loading rooms" })

            state.storageError -> StorageErrorContent(state, onRetryStorage, onResetStorage, resetting = resetting,
                onResettingChanged = { resetting = it })

            !returning -> ColdContent(
                layout = layout, state = state, enabled = enabled,
                onRoomNameChanged = onRoomNameChanged, onAnonymousModeChanged = onAnonymousModeChanged, onStartRoom = onStartRoom,
                onConferenceLengthChanged = onConferenceLengthChanged, onRoomDestructChanged = onRoomDestructChanged,
                onJoinUrlChanged = onJoinUrlChanged, onJoin = onJoin, onSignIn = onSignIn, onOpenProjects = onOpenProjects, onAddOfferedCard = onAddOfferedCard,
                onDismissCardOffer = onDismissCardOffer, onStopOpening = onStopOpening, vmlsRooms = vmlsRooms,
            )

            else -> ReturningContent(
                layout = layout, state = state, enabled = enabled, homeRooms = homeRooms, orderedRooms = orderedRooms,
                sections = sections, foldOpen = foldOpen,
                onFoldToggled = { section ->
                    val open = foldOpen[section] != true
                    foldOpen[section] = open
                    prefs.edit().putBoolean(foldPreference(section), open).apply()
                },
                query = query, onQueryChanged = { query = it }, projectTab = projectTab,
                onProjectTabChanged = { projectTab = it; prefs.edit().putString("projectTab", it).apply() },
                openRoom = ::openRoom, onRetrySync = accountRooms.refresh, actionsFor = ::actionsFor, callRoomId = callRoomId,
                now = now, zone = zone, locale = locale, is24Hour = is24Hour,
                listState = listState, newRoomOpen = newRoomOpen, onNewRoomOpenChanged = { newRoomOpen = it },
                onRoomNameChanged = onRoomNameChanged, onAnonymousModeChanged = onAnonymousModeChanged, onStartRoom = onStartRoom,
                onConferenceLengthChanged = onConferenceLengthChanged, onRoomDestructChanged = onRoomDestructChanged,
                onJoinUrlChanged = onJoinUrlChanged, onJoin = onJoin, onSignIn = onSignIn, onOpenProjects = onOpenProjects,
                onAddOfferedCard = onAddOfferedCard, onDismissCardOffer = onDismissCardOffer, onStopOpening = onStopOpening,
                vmlsRooms = vmlsRooms, onDismissTombstone = onDismissTombstone,
            )
        }
    }

    pairingRoom?.let { room ->
        AlertDialog(onDismissRequest = { pairingRoom = null }, title = { Text("Connect Bothy") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Paste the Bothy pairing code. Bothy will learn your public identity, ${state.account?.npub ?: "the signed-in account"}, for this room. If this account created the conversation, KithMoot asks your signer for a 30-day, revocable message grant for the other person's current signed device. Otherwise, the creator must already have issued your grant. KithMoot switches only after Bothy confirms access.")
                OutlinedTextField(pairingCode, { pairingCode = it }, Modifier.fillMaxWidth(), label = { Text("Bothy pairing code") }, minLines = 3)
                TextButton(
                    onClick = { clipboardText(context)?.let { pairingCode = it } },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Paste Bothy pairing code from clipboard" },
                ) { Text("Paste from clipboard") }
                OutlinedButton(
                    onClick = { scanningPairingCode = true },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Scan Bothy QR" },
                ) { Text("Scan Bothy QR") }
            } },
            confirmButton = { Button({ onPairBothy(room.id, pairingCode); pairingRoom = null }, enabled = enabled && pairingCode.isNotBlank()) { Text("Connect and verify") } },
            dismissButton = { TextButton({ pairingRoom = null }) { Text("Cancel") } })
    }
    if (scanningPairingCode) {
        Dialog(onDismissRequest = { scanningPairingCode = false }) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Scan Bothy QR", style = MaterialTheme.typography.titleLarge)
                    Text("Point at the code Bothy is showing. KithMoot will still verify it before changing this room's route.")
                    QrScanner(
                        accept = { it.trim().startsWith("bothy:") },
                        onDecoded = { pairingCode = it; scanningPairingCode = false },
                        prompt = "KithMoot needs the camera to scan Bothy's pairing QR.",
                    )
                    TextButton({ scanningPairingCode = false }, Modifier.heightIn(min = 48.dp)) { Text("Cancel scan") }
                }
            }
        }
    }
    disconnectingRoom?.let { room ->
        AlertDialog(onDismissRequest = { disconnectingRoom = null }, title = { Text("Disconnect Bothy?") },
            text = { Text(if (room.id in state.linkGrantOwnerRooms)
                "KithMoot will ask Bothy to revoke this room's guest-device grants, wait for confirmation, then return ${room.name} to its earlier relays and remove the local Link route."
            else "${room.name} will return to its earlier relays and this device's local Link route will be removed. Any remote grant issued by the conversation creator remains under their control until they revoke it or it expires.") },
            confirmButton = { Button({ onDisconnectBothy(room.id); disconnectingRoom = null }, enabled = enabled) { Text("Disconnect") } },
            dismissButton = { TextButton({ disconnectingRoom = null }) { Text("Cancel") } })
    }
    revokingRoom?.let { room ->
        AlertDialog(onDismissRequest = { revokingRoom = null }, title = { Text("Revoke guest access?") },
            text = { Text("Bothy will close the other person's live subscriptions and refuse their next reads and writes. This device stays connected and keeps the room's acknowledged ciphertext.") },
            confirmButton = { Button({ onRevokeBothyGuests(room.id); revokingRoom = null }, enabled = enabled) { Text("Revoke access") } },
            dismissButton = { TextButton({ revokingRoom = null }) { Text("Cancel") } })
    }
    forgetting?.let { room ->
        AlertDialog(onDismissRequest = { forgetting = null }, title = { Text("Remove ${room.name} from this phone?") },
            text = { Text("Remove this room and your identity for it from this device. Creator controls saved here will be lost. " +
                "Your synced account bookmark and other members are unaffected. Returning needs a working saved invitation or pairing link.") },
            confirmButton = { TextButton({ forgetting = null; onForget(room.id) }) { Text("Remove from this phone") } },
            dismissButton = { TextButton({ forgetting = null }) { Text("Keep room") } })
    }
    removingAccountRoom?.let { room ->
        AlertDialog(onDismissRequest = { removingAccountRoom = null }, title = { Text("Remove ${room.label} from your account?") },
            text = { Text("This removes its bookmark from your synced account list on all devices. It does not delete messages, revoke access or erase rooms saved on this phone.") },
            confirmButton = { TextButton({ removingAccountRoom = null; accountRooms.remove(room.roomId) }) { Text("Remove from account") } },
            dismissButton = { TextButton({ removingAccountRoom = null }) { Text("Cancel") } })
    }
    filing?.let { room ->
        val existing = state.savedRooms.mapNotNull { it.project }.distinct().sorted()
        AlertDialog(onDismissRequest = { filing = null }, title = { Text("Local group for ${room.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("A label on this device, so rooms for one piece of work sit together. Leave it empty to take the room out of its local group.")
                    OutlinedTextField(filedAs, { filedAs = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Local group") })
                    if (existing.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (name in existing) OutlinedButton({ filedAs = name }) { Text(name) }
                    }
                }
            },
            confirmButton = { TextButton({ filing = null; onProject(room.id, filedAs) }) { Text("Save") } },
            dismissButton = { TextButton({ filing = null }) { Text("Cancel") } })
    }
}

@Composable
private fun StorageErrorContent(state: StartState, onRetryStorage: () -> Unit, onResetStorage: () -> Unit,
    resetting: Boolean, onResettingChanged: (Boolean) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Saved rooms are unavailable", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Text("Your saved data has been kept. Try again before deleting anything.")
                OutlinedButton(onRetryStorage, enabled = !state.busy && !state.loadingRooms) { Text("Try again") }
                TextButton({ onResettingChanged(true) }, enabled = !state.busy && !state.loadingRooms) { Text("Delete saved rooms…") }
            }
        }
        NewRoomForm(state.roomName, {}, state.anonymousMode, {}, enabled = false, busy = false, error = null, onStartRoom = {})
    }
    if (resetting) {
        AlertDialog(onDismissRequest = { onResettingChanged(false) }, title = { Text("Delete all saved rooms?") },
            text = { Text("Permanently remove every saved room and identity from this device. You may lose access to rooms you created. " +
                "Other members and relay messages are unaffected. This cannot be undone.") },
            confirmButton = { TextButton({ onResettingChanged(false); onResetStorage() }) { Text("Delete saved rooms") } },
            dismissButton = { TextButton({ onResettingChanged(false) }) { Text("Keep saved data") } })
    }
}

/** The preamble every state shares: busy and opening progress, errors and
 *  the contact-card offer, above whichever body follows. */
@Composable
private fun ColumnScope.Preamble(state: StartState, onAddOfferedCard: () -> Unit, onDismissCardOffer: () -> Unit, onStopOpening: () -> Unit) {
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = state.opening ?: "Opening room" })
    // Named, so a slow relay reads as a room on its way rather than a tap that did nothing.
    if (state.busy) state.opening?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    // A room that will not open must never be a reason to quit the app.
    if (state.canStopOpening) OutlinedButton(onStopOpening, Modifier.heightIn(min = 48.dp)) { Text("Stop and go back to your rooms") }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    val offer = state.cardOffer
    if (offer != null) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This is a contact card", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
                val who = offer.name?.let { "From $it" } ?: "From a person with no name on their card"
                val boxes = when (offer.boxes) { 0 -> "no box"; 1 -> "one box"; else -> "${offer.boxes} boxes" }
                Text(if (offer.added) "${offer.name ?: "They"} ${if (offer.name != null) "is" else "are"} in your contacts on this phone. Their card is kept on this phone."
                    else "$who, naming $boxes. Add the card to keep their details on this phone.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!offer.added) Button(onAddOfferedCard, Modifier.heightIn(min = 48.dp)) { Text("Add to contacts") }
                    OutlinedButton(onDismissCardOffer, Modifier.heightIn(min = 48.dp)) { Text(if (offer.added) "Done" else "Not now") }
                }
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.ColdContent(
    layout: HomeLayout, state: StartState, enabled: Boolean,
    onRoomNameChanged: (String) -> Unit, onAnonymousModeChanged: (Boolean) -> Unit, onStartRoom: () -> Unit,
    onConferenceLengthChanged: (ConferenceLength) -> Unit,
    onRoomDestructChanged: (Boolean) -> Unit = {},
    onJoinUrlChanged: (String) -> Unit, onJoin: () -> Unit, onSignIn: () -> Unit, onOpenProjects: () -> Unit,
    onAddOfferedCard: () -> Unit, onDismissCardOffer: () -> Unit, onStopOpening: () -> Unit,
    vmlsRooms: (@Composable () -> Unit)?,
) {
    val twoColumn = layout == HomeLayout.SHORT || layout == HomeLayout.EXPANDED
    val maxContentWidth = if (layout == HomeLayout.MEDIUM) 560.dp else Dp.Unspecified

    @Composable
    fun Intro() {
        Text("Start a room, then send the link.", style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.semantics { heading() })
        Text("A workspace nobody owns: messages, files and calls for your people and your agents. No account needed.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    @Composable
    fun Foot() {
        InviteLinkSection(state.joinUrl, onJoinUrlChanged, enabled, onJoin)
        TextButton(onOpenProjects, Modifier.heightIn(min = 48.dp)) { Text("Projects") }
        if (state.account == null) TextButton(onSignIn, Modifier.heightIn(min = 48.dp)) { Text("Already on Nostr? Sign in") }
    }

    if (twoColumn) {
        Row(Modifier.fillMaxSize().widthIn(max = 960.dp).align(Alignment.TopCenter).padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Preamble(state, onAddOfferedCard, onDismissCardOffer, onStopOpening)
                Intro()
                vmlsRooms?.invoke()
            }
            Column(Modifier.weight(1f).imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                NewRoomForm(state.roomName, onRoomNameChanged, state.anonymousMode, onAnonymousModeChanged,
                    enabled, state.busy, state.error, onStartRoom,
                    conferenceLength = state.conferenceLength, onConferenceLengthChanged = onConferenceLengthChanged,
                    roomDestruct = state.roomDestruct, onRoomDestructChanged = onRoomDestructChanged)
                Foot()
            }
        }
    } else {
        Column(
            Modifier.fillMaxSize().let { if (maxContentWidth != Dp.Unspecified) it.widthIn(max = maxContentWidth) else it }
                .align(Alignment.TopCenter).navigationBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Preamble(state, onAddOfferedCard, onDismissCardOffer, onStopOpening)
            Intro()
            vmlsRooms?.invoke()
            NewRoomForm(state.roomName, onRoomNameChanged, state.anonymousMode, onAnonymousModeChanged,
                enabled, state.busy, state.error, onStartRoom,
                conferenceLength = state.conferenceLength, onConferenceLengthChanged = onConferenceLengthChanged,
                    roomDestruct = state.roomDestruct, onRoomDestructChanged = onRoomDestructChanged)
            Foot()
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun BoxWithConstraintsScope.ReturningContent(
    layout: HomeLayout, state: StartState, enabled: Boolean, homeRooms: List<HomeRoom>, orderedRooms: List<HomeRoom>,
    sections: Map<String, HomeSection>, foldOpen: Map<HomeSection, Boolean>, onFoldToggled: (HomeSection) -> Unit,
    query: String, onQueryChanged: (String) -> Unit, projectTab: String, onProjectTabChanged: (String) -> Unit,
    openRoom: (HomeRoom) -> Unit, actionsFor: (HomeRoom) -> List<ConversationAction>, callRoomId: String?,
    now: Long, zone: java.time.ZoneId, locale: java.util.Locale, is24Hour: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState, newRoomOpen: Boolean, onNewRoomOpenChanged: (Boolean) -> Unit,
    onRoomNameChanged: (String) -> Unit, onAnonymousModeChanged: (Boolean) -> Unit, onStartRoom: () -> Unit,
    onConferenceLengthChanged: (ConferenceLength) -> Unit,
    onRoomDestructChanged: (Boolean) -> Unit = {},
    onJoinUrlChanged: (String) -> Unit, onJoin: () -> Unit, onSignIn: () -> Unit, onOpenProjects: () -> Unit,
    onAddOfferedCard: () -> Unit, onDismissCardOffer: () -> Unit, onStopOpening: () -> Unit, onRetrySync: () -> Unit,
    vmlsRooms: (@Composable () -> Unit)?,
    onDismissTombstone: (String) -> Unit = {},
) {
    val projectsAvailable = remember(homeRooms) { homeRooms.mapNotNull { it.project }.distinct().sorted() }
    val tab = if (projectTab.isNotEmpty() && projectTab != NO_PROJECT_TAB && projectTab !in projectsAvailable) "" else projectTab
    val byProject = orderedRooms.filter { tab.isEmpty() || (if (tab == NO_PROJECT_TAB) it.project == null else it.project == tab) }
    val groups = groupRooms(byProject, query, sections)
    val filtered = byProject.filter { matchesQuery(it, query) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var inviteOpen by rememberSaveable { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    val showSearch = homeRooms.size >= 8

    val expanded = layout == HomeLayout.EXPANDED
    val maxListWidth = when (layout) { HomeLayout.MEDIUM -> 640.dp; HomeLayout.EXPANDED -> 640.dp; else -> Dp.Unspecified }

    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searchOpen) { if (searchOpen) runCatching { searchFocus.requestFocus() } }

    @Composable
    fun ListPane(modifier: Modifier) {
        // Padded for the keyboard: the invite field is this list's last item,
        // and without it the keyboard covers the field and its Open button.
        LazyColumn(modifier.imePadding(), state = listState, contentPadding = PaddingValues(top = if (expanded) 0.dp else 16.dp, bottom = if (expanded) 24.dp else 96.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Preamble(state, onAddOfferedCard, onDismissCardOffer, onStopOpening)
                    vmlsRooms?.invoke()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Rooms", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                        if (showSearch) IconButton({ if (searchOpen) onQueryChanged(""); searchOpen = !searchOpen }, Modifier.size(48.dp)) {
                            Icon(if (searchOpen) Icons.Filled.Close else Icons.Filled.Search, if (searchOpen) "Close search" else "Search rooms")
                        }
                        TextButton(onOpenProjects) { Text("Projects") }
                        Box {
                            IconButton({ overflowOpen = true }, Modifier.size(48.dp)) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(overflowOpen, { overflowOpen = false }) {
                                DropdownMenuItem(text = { Text("Open invite link") }, onClick = { overflowOpen = false; inviteOpen = true })
                                if (state.account == null) DropdownMenuItem(text = { Text("Sign in") }, onClick = { overflowOpen = false; onSignIn() })
                            }
                        }
                    }
                    if (showSearch && (searchOpen || query.isNotEmpty())) OutlinedTextField(query, onQueryChanged, Modifier.fillMaxWidth().focusRequester(searchFocus), singleLine = true,
                        label = { Text("Find a room") },
                        trailingIcon = { if (query.isNotEmpty()) IconButton({ onQueryChanged("") }) {
                            Icon(Icons.Filled.Close, "Clear search")
                        } })
                    if (query.isNotBlank()) {
                        val label = if (filtered.isEmpty()) "No rooms match “${query.trim()}”."
                            else if (filtered.size == 1) "1 room found" else "${filtered.size} rooms found"
                        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    } else if (tab.isNotEmpty() && filtered.isEmpty()) {
                        Text("No rooms in this project.", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    if (projectsAvailable.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val tabs = listOf("" to "All") + projectsAvailable.map { it to it } + listOf(NO_PROJECT_TAB to "No project")
                            for ((value, label) in tabs) FilterChip(
                                selected = value == tab, onClick = { onProjectTabChanged(value) }, label = { Text(label) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                    if (state.account != null && homeRooms.isEmpty()) {
                        val syncing = state.roomBookmarks.syncing
                        val error = state.roomSyncError ?: state.roomBookmarks.error
                        when {
                            syncing -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Looking for rooms saved to your account…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                            error != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                OutlinedButton(onRetrySync) { Text("Try again") }
                            }
                            else -> Text("No rooms yet. Start one, or open an invite link you were sent.",
                                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            for (group in groups) {
                val section = group.section
                val open = section == null || !section.foldable || foldOpen[section] == true
                if (section != null) item(key = "heading-${section.name}") {
                    SectionHeading(section, group.rooms.size, folded = !open, onToggle = { onFoldToggled(section) })
                }
                if (open) items(group.rooms, key = { it.id }) { room ->
                    val rowState = roomRowState(room, null, callRoomId, state.account?.pubkey, now, zone, locale, is24Hour)
                    RoomRow(room.id, room.label, rowState.status, rowState.time, rowState.timeSpoken, enabled, { openRoom(room) }, actionsFor(room),
                        pinned = room.pinned, ended = room.ended || conferenceEnded(room.endsAt, now),
                        countdown = if (showsCountdown(room, now)) ({
                            dev.forgesworn.kithmoot.ui.room.CountdownPill(room.endsAt!!, room.startsAt, room.destruct,
                                dev.forgesworn.kithmoot.ui.room.rememberNow())
                        }) else null)
                }
            }
            // Rooms that self-destructed here: greyed, naming none (D2), until dismissed or seven days pass.
            if (query.isBlank()) items(state.destructTombstones, key = { "tombstone-" + it.id }) { tombstone ->
                TombstoneRow(tombstone.at, zone, locale) { onDismissTombstone(tombstone.id) }
            }
        }
    }

    if (expanded) {
        Row(Modifier.fillMaxSize().widthIn(max = 1040.dp).align(Alignment.TopCenter).padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            ListPane(Modifier.weight(1f).widthIn(max = maxListWidth))
            Column(Modifier.width(360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("New room", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                NewRoomForm(state.roomName, onRoomNameChanged, state.anonymousMode, onAnonymousModeChanged,
                    enabled, state.busy, state.error, onStartRoom,
                    conferenceLength = state.conferenceLength, onConferenceLengthChanged = onConferenceLengthChanged,
                    roomDestruct = state.roomDestruct, onRoomDestructChanged = onRoomDestructChanged)
            }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            ListPane(Modifier.fillMaxSize().align(Alignment.TopCenter)
                .let { if (maxListWidth != Dp.Unspecified) it.widthIn(max = maxListWidth) else it }
                .padding(horizontal = if (layout == HomeLayout.COMPACT) 16.dp else 24.dp))
            // The text/icon overload clears its label's semantics, so TalkBack
            // and UI Automator saw an unlabelled button; this overload keeps it.
            ExtendedFloatingActionButton(
                onClick = { onNewRoomOpenChanged(true) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).navigationBarsPadding(),
            ) {
                Icon(Icons.Filled.Add, null)
                Spacer(Modifier.width(12.dp))
                Text("New room")
            }
        }
    }

    if (inviteOpen) {
        ModalBottomSheet(onDismissRequest = { inviteOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Open invite link", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                InviteLinkSection(state.joinUrl, onJoinUrlChanged, enabled, { inviteOpen = false; onJoin() }, collapsible = false)
            }
        }
    }

    if (newRoomOpen) {
        ModalBottomSheet(onDismissRequest = { onNewRoomOpenChanged(false) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("New room", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                NewRoomForm(state.roomName, onRoomNameChanged, state.anonymousMode, onAnonymousModeChanged,
                    enabled, state.busy, state.error, onStartRoom, onCancel = { onNewRoomOpenChanged(false) },
                    conferenceLength = state.conferenceLength, onConferenceLengthChanged = onConferenceLengthChanged,
                    roomDestruct = state.roomDestruct, onRoomDestructChanged = onRoomDestructChanged)
            }
        }
    }
}

private const val NO_PROJECT_TAB = "\u0000none"

private fun foldPreference(section: HomeSection) = "homeFoldOpen.${section.name.lowercase()}"

/** A small heading row. Older and Ended are fold buttons; the rest are plain headings. */
@Composable
private fun SectionHeading(section: HomeSection, count: Int, folded: Boolean, onToggle: () -> Unit) {
    val text = sectionHeading(section, count, folded)
    val style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.04.em)
    val colour = MaterialTheme.colorScheme.onSurfaceVariant
    if (section.foldable) {
        Text(text, style = style, color = colour,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { heading(); stateDescription = if (folded) "Collapsed" else "Expanded" }
                .wrapContentHeight(Alignment.CenterVertically))
    } else {
        Text(text, style = style, color = colour, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp).semantics { heading() })
    }
}
