package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.ui.StartState
import dev.forgesworn.kithmoot.ui.room.PublicProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** One person People lists: under Contacts (Signet or a card) or under Following. */
internal data class Person(
    val pubkey: String,
    /** The name this app was given for them: Signet's, else the one on their card. */
    val name: String?,
    val signet: Boolean = false,
    val card: Boolean = false,
    val follow: Boolean = false,
    /** Kin, Kith or Ken, when Signet shared it. */
    val tier: String? = null,
    /** Signet says their key is checked. */
    val checked: Boolean = false,
)

/**
 * A row's second line, as on the web: a Signet contact's tier and "checked"
 * when the directory shares them, else "Signet"; a card's "Contact card".
 * Someone only followed shows nothing: a Nostr address would be shown only
 * once verified, and this app does not verify them, so it shows none.
 */
internal fun personSubline(person: Person): String? = when {
    person.signet -> listOfNotNull(person.tier, "checked".takeIf { person.checked }).joinToString(" · ").ifEmpty { "Signet" }
    person.card -> "Contact card"
    else -> null
}

/** People's two sections, Contacts and Following, filtered by [query]. */
internal data class PeopleSections(val contacts: List<Person>, val following: List<Person>)

/** What a row says: the given name, else the public profile's, else a short key (never a full npub). */
internal fun personLabel(person: Person, profiles: Map<String, PublicProfile>): String =
    person.name ?: profiles[person.pubkey]?.name ?: shortNpub(person.pubkey)

/**
 * People's lists (design-calm-shell 6.1): Contacts are the Signet directory
 * and the contact cards on this phone, merged by key, A to Z; Following is
 * the account's public follow list in its own order, without anyone already
 * under Contacts. Anyone blocked, in Signet or on this phone, is in neither;
 * Settings › Privacy lists this phone's blocks.
 */
internal fun peopleSections(state: StartState, query: String = ""): PeopleSections {
    val blocked = state.signetContacts.blocked + state.blockedHere.map { it.participant }
    val signet = state.signetContacts.contacts.filter { it.pubkey !in blocked }
    val cards = state.contactCards.filter { it.p !in blocked }
    val byKey = linkedMapOf<String, Person>()
    for (c in signet) byKey[c.pubkey] = Person(c.pubkey, c.name, signet = true, tier = c.tier, checked = c.checked)
    for (c in cards) byKey[c.p] = byKey[c.p]?.let { it.copy(card = true, name = it.name ?: c.name) } ?: Person(c.p, c.name, card = true)
    val follows = state.follows.orEmpty().toSet()
    val contacts = byKey.values.map { it.copy(follow = it.pubkey in follows) }
        .sortedWith(compareBy<Person>({ personLabel(it, state.followProfiles).lowercase() }, { it.pubkey }))
    // A to Z by the name on their public profile, as on the web; anyone not
    // yet named goes last, by key, and moves up as names arrive.
    val following = state.follows.orEmpty().distinct().filter { it !in byKey && it !in blocked }.map { Person(it, null, follow = true) }
        .sortedWith(compareBy<Person>({ state.followProfiles[it.pubkey]?.name == null },
            { state.followProfiles[it.pubkey]?.name?.lowercase() }, { it.pubkey }))
    fun matches(person: Person): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return personLabel(person, state.followProfiles).contains(q, true) ||
            dev.forgesworn.kithmoot.account.npubOf(person.pubkey).startsWith(q, true) || person.pubkey.startsWith(q, true)
    }
    return PeopleSections(contacts.filter(::matches), following.filter(::matches))
}

/**
 * Whether Message, Voice and Video can reach [person] on today's plumbing:
 * a private inbox open, and either a Signet-granted contact or someone the
 * account follows (the owner's rule; the view model enforces the same rule
 * in `mayReachContact`). A card alone is not enough.
 */
internal fun canReach(state: StartState, person: Person): Boolean {
    if (!state.contactInbox.ready || person.pubkey in state.signetContacts.blocked || state.blockedHere.any { it.participant == person.pubkey }) return false
    val granted = person.signet && state.signetContacts.status in setOf("ready", "stale")
    return granted || person.pubkey in state.follows.orEmpty()
}

/** What People does: the Signet contact actions, the follow list, the cards on this phone, and calls. */
data class PeopleActions(
    val contacts: SignetContactActions = SignetContactActions(),
    val lookUpFollows: (Boolean) -> Unit = {},
    val lookUpProfiles: (List<String>) -> Unit = {},
    val refreshCards: () -> Unit = {},
    val addCard: (String) -> Unit = {},
    val forgetCard: (String) -> Unit = {},
    val checkBox: (String, String, String, Boolean) -> Unit = { _, _, _, _ -> },
    val showMyCard: () -> Unit = {},
    val dismissMyCard: () -> Unit = {},
    /** Opens or creates the private chat, then starts a call: the microphone on, and the camera too when true. */
    val call: (String, Boolean) -> Unit = { _, _ -> },
    /** Blocks on this phone: never published, and they are not told. They leave People. */
    val block: (String) -> Unit = {},
)

/**
 * People: the signed-in account's contacts as a destination, where the
 * account menu's Contacts used to be (design-calm-shell 6.1). Selecting a
 * person shows Message, Voice and Video under their row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleDestination(
    state: StartState,
    actions: PeopleActions,
    insets: WindowInsets = TopAppBarDefaults.windowInsets,
    accountButton: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** Opens with this person selected; for previews and captures. */
    initialSelected: String? = null,
) {
    var query by rememberSaveable(state.account?.pubkey) { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable(state.account?.pubkey) { mutableStateOf(initialSelected) }
    var addOpen by rememberSaveable { mutableStateOf(false) }
    var cardsOpen by rememberSaveable { mutableStateOf(false) }
    var linkOpen by rememberSaveable { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // Names arrive while the list is read; a row under the finger keeps its
    // place until scrolling stops, then the list settles A to Z.
    val sorted = peopleSections(state, query)
    var heldFollowing by remember { mutableStateOf<List<String>?>(null) }
    val followingOrder = holdOrder(heldFollowing, sorted.following.map { it.pubkey }, listState.isScrollInProgress)
    androidx.compose.runtime.SideEffect { heldFollowing = followingOrder }
    val followingByKey = sorted.following.associateBy { it.pubkey }
    val sections = sorted.copy(following = followingOrder.mapNotNull(followingByKey::get))

    // The follow list once per account, and the cards on this phone, when People opens.
    LaunchedEffect(state.account?.pubkey, state.publicProfiles) { actions.lookUpFollows(false) }
    LaunchedEffect(Unit) { actions.refreshCards() }
    // Names and pictures for the rows on screen only, once the list settles.
    LaunchedEffect(listState, state.publicProfiles) {
        if (!state.publicProfiles) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { (it.key as? String)?.substringAfter("person-", "")?.takeIf { k -> k.isNotEmpty() } } }
            .collectLatest { keys -> delay(PROFILE_SETTLE_MS); if (keys.isNotEmpty()) actions.lookUpProfiles(keys) }
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("People", style = dev.forgesworn.kithmoot.ui.theme.cappedTitleStyle(), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
            actions = {
                IconButton({ if (searchOpen) query = ""; searchOpen = !searchOpen }, Modifier.size(48.dp)) {
                    Icon(if (searchOpen) Icons.Filled.Close else Icons.Filled.Search, if (searchOpen) "Close search" else "Search people")
                }
                IconButton({ addOpen = true }, Modifier.size(48.dp)) { Icon(Icons.Outlined.PersonAdd, "Add a contact") }
                accountButton?.invoke()
            },
            windowInsets = insets,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        )
        if (searchOpen || query.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp), singleLine = true,
                label = { Text("Find a person") },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Close, "Clear search") } })
        }
        LazyColumn(Modifier.fillMaxSize().imePadding(), state = listState,
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 32.dp)) {
            item(key = "notes") {
                Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.contactsError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    state.signetContacts.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.signetContacts.status == "stale") Text("Showing saved contact names. Link Signet again to update them.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.contactInbox.pending > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (state.contactInbox.pending == 1) "1 invitation waiting to send." else "${state.contactInbox.pending} invitations waiting to send.",
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(actions.contacts.retry, Modifier.heightIn(min = 48.dp), enabled = state.contactInbox.ready) { Text("Try again") }
                    }
                }
            }
            val nobody = state.signetContacts.contacts.isEmpty() && state.contactCards.isEmpty()
            if (nobody && query.isBlank()) item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("No contacts yet.", style = MaterialTheme.typography.titleMedium)
                    Button({ addOpen = true }, Modifier.heightIn(min = 48.dp)) { Text("Add a contact") }
                    OutlinedButton({ linkOpen = true }, Modifier.heightIn(min = 48.dp), enabled = state.account != null) { Text("Link Signet contacts") }
                }
            }
            if (sections.contacts.isNotEmpty()) {
                item(key = "heading-contacts") { PeopleHeading("Contacts") }
                items(sections.contacts, key = { "contact-person-" + it.pubkey }) { person ->
                    PersonRow(state, person, selected = selected == person.pubkey, actions = actions,
                        onSelect = { selected = if (selected == person.pubkey) null else person.pubkey })
                }
            }
            if (!state.publicProfiles) {
                item(key = "heading-following") { PeopleHeading("Following") }
                item(key = "following-off") {
                    Text("Turn on public profiles in Settings to see the people you follow.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
                }
            } else if (state.followsLoading && state.follows == null) {
                item(key = "heading-following") { PeopleHeading("Following") }
                item(key = "following-loading") {
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Looking up the people you follow…", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            } else if (state.followsError != null && state.follows == null) {
                item(key = "heading-following") { PeopleHeading("Following") }
                item(key = "following-error") {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.followsError, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton({ actions.lookUpFollows(true) }, Modifier.heightIn(min = 48.dp)) { Text("Try again") }
                    }
                }
            } else if (sections.following.isNotEmpty()) {
                item(key = "heading-following") { PeopleHeading("Following") }
                items(sections.following, key = { "follow-person-" + it.pubkey }) { person ->
                    PersonRow(state, person, selected = selected == person.pubkey, actions = actions,
                        onSelect = { selected = if (selected == person.pubkey) null else person.pubkey })
                }
            }
            if (query.isNotBlank() && sections.contacts.isEmpty() && sections.following.isEmpty()) item(key = "no-match") {
                Text("Nobody matches “${query.trim()}”.", Modifier.padding(horizontal = 8.dp, vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }

    if (addOpen) AddContactSheet(
        onDismiss = { addOpen = false },
        onPaste = { addOpen = false; cardsOpen = true },
        onScan = { addOpen = false; scanning = true },
        onLink = { addOpen = false; linkOpen = true },
        onShowMyCard = { addOpen = false; actions.showMyCard(); cardsOpen = true },
        signedIn = state.account != null,
    )
    if (cardsOpen) ModalBottomSheet(
        onDismissRequest = { cardsOpen = false; actions.dismissMyCard() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        dev.forgesworn.kithmoot.ui.room.ContactCardsSheet(
            contacts = state.contactCards, status = state.contactCardStatus, myCard = state.myAccountCard,
            canShowCard = state.account != null, onAdd = actions.addCard, onForget = actions.forgetCard,
            readRelays = state.relays, onCheckBox = actions.checkBox, onShowMyCard = actions.showMyCard,
            onDone = { cardsOpen = false; actions.dismissMyCard() },
            relaysOnCard = "your relays",
        )
    }
    if (linkOpen) dev.forgesworn.kithmoot.ui.settings.SettingsSheet("Link Signet contacts", { linkOpen = false }) {
        SignetLinkSection(state, actions.contacts)
    }
    if (scanning) androidx.compose.ui.window.Dialog(onDismissRequest = { scanning = false }) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Scan a QR code", style = MaterialTheme.typography.titleLarge)
                Text("Point the camera at someone's contact card. It is kept on this phone only.")
                dev.forgesworn.kithmoot.ui.qr.QrScanner(
                    accept = { it.trim().length in 1..8192 },
                    onDecoded = { scanning = false; actions.addCard(it.trim()); cardsOpen = true },
                    prompt = "KithMoot needs the camera to scan a contact card.",
                )
                TextButton({ scanning = false }, Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            }
        }
    }
}

/** How long the list must sit still before its rows' names are looked up. */
private const val PROFILE_SETTLE_MS = 350L

@Composable
private fun PeopleHeading(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 12.dp, bottom = 4.dp).semantics { heading() })
}

/** A person's row; selected, it holds their actions under it (the Android mock-up's inline actions). */
@Composable
private fun PersonRow(state: StartState, person: Person, selected: Boolean, actions: PeopleActions, onSelect: () -> Unit) {
    val label = personLabel(person, state.followProfiles)
    val profile = state.followProfiles[person.pubkey].takeIf { state.publicProfiles }
    val subline = personSubline(person)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .then(if (selected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(onClickLabel = if (selected) "Hide actions" else "Show actions", role = Role.Button, onClick = onSelect)
            .semantics(mergeDescendants = true) { this.selected = selected; if (selected) stateDescription = "Actions shown" }
            .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PersonAvatar(person.pubkey, label, profile)
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                subline?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        if (selected) {
            val reachable = canReach(state, person)
            val enabled = reachable && !state.busy && !state.contactsBusy
            // Side by side as in the mock-up; one per line once text is large, so no label is cut.
            if (LocalDensity.current.fontScale >= 1.5f) Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PersonAction(Icons.Outlined.ChatBubbleOutline, "Message", enabled, Modifier.fillMaxWidth(), stacked = true) { actions.contacts.message(person.pubkey) }
                PersonAction(Icons.Outlined.Call, "Voice", enabled, Modifier.fillMaxWidth(), stacked = true) { actions.call(person.pubkey, false) }
                PersonAction(Icons.Outlined.Videocam, "Video", enabled, Modifier.fillMaxWidth(), stacked = true) { actions.call(person.pubkey, true) }
            } else Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PersonAction(Icons.Outlined.ChatBubbleOutline, "Message", enabled, Modifier.weight(1f)) { actions.contacts.message(person.pubkey) }
                PersonAction(Icons.Outlined.Call, "Voice", enabled, Modifier.weight(1f)) { actions.call(person.pubkey, false) }
                PersonAction(Icons.Outlined.Videocam, "Video", enabled, Modifier.weight(1f)) { actions.call(person.pubkey, true) }
            }
            if (!reachable) Text(
                if (!state.contactInbox.ready) "Opening your private inbox…"
                else "Link Signet contacts or follow them on Nostr to message them here.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
            // Block, last (design-calm-shell 6.1). They leave the list; Settings › Privacy undoes it.
            if (state.contactInbox.ready) Column(Modifier.fillMaxWidth()
                .clickable(onClickLabel = "Block", role = Role.Button) { actions.block(person.pubkey) }
                .semantics(mergeDescendants = true) {}
                .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Block $label", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                Text("They aren’t told. Settings › Privacy undoes it.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PersonAction(icon: ImageVector, label: String, enabled: Boolean, modifier: Modifier, stacked: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface, modifier = modifier.heightIn(min = if (stacked) 56.dp else 64.dp)) {
        val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        if (stacked) Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, tint = tint)
            Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
        } else Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = tint)
            Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
        }
    }
}

/** Add a contact (design-calm-shell 6.1): paste a card, scan one, link Signet, or show your own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddContactSheet(onDismiss: () -> Unit, onPaste: () -> Unit, onScan: () -> Unit, onLink: () -> Unit,
    onShowMyCard: () -> Unit, signedIn: Boolean) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Add a contact", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() })
            AddItem(Icons.Outlined.ContentPaste, "Paste a contact card or its link", onPaste)
            AddItem(Icons.Outlined.QrCodeScanner, "Scan a QR code", onScan)
            if (signedIn) AddItem(Icons.Outlined.Link, "Link Signet contacts", onLink)
            if (signedIn) AddItem(Icons.Outlined.Badge, "Show my card", onShowMyCard)
        }
    }
}

@Composable
private fun AddItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
