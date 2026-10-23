package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ViewKanban
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.account.shortNpub
import dev.forgesworn.kithmoot.ui.RoomState

/*
 * Room info (design-calm-shell 5.4, 9.5): one full-screen page in place of
 * the long details sheet, with its rows opening subpages in the same place.
 * The order and the words are the web's. What each subpage holds is the
 * room screen's own controls, passed in, so they keep their behaviour.
 */

internal enum class RoomInfoPage(val title: String) {
    MAIN("Room info"), INVITE("Invite link"), PRIVACY("Privacy and connection"), NAME("Name and picture"),
}

/** The line under the room's name in Room info, as the web writes it. */
internal fun roomInfoWho(state: RoomState): String {
    if (state.privateConversation) return if (state.quiet) "Quiet chat · Only members can join" else "Private chat · Only members can join"
    val agents = state.tiles.count { it.participant in state.agentParticipants }
    val people = state.tiles.size - agents
    val who = if (state.tiles.isEmpty()) "" else "$people ${if (people == 1) "person" else "people"}" +
        if (agents > 0) ", $agents agent${if (agents == 1) "" else "s"}" else ""
    return listOf(who, "Anyone with the link can join").filter { it.isNotEmpty() }.joinToString(" · ")
}

/** "Ends in 3 days · Self-destructs", or empty for a room that does not end. */
internal fun roomInfoEnds(endsAt: Long?, destruct: Boolean, endsLine: (Long) -> String = { dev.forgesworn.kithmoot.session.conferenceEndsLine(it) }): String {
    val ends = endsAt?.let(endsLine).orEmpty()
    if (ends.isEmpty()) return if (destruct) "Self-destructs if it is ended for everyone" else ""
    return listOf(ends, if (destruct) "Self-destructs" else "").filter { it.isNotEmpty() }.joinToString(" · ")
}

/** How the room's messages travel, for the Privacy and connection row. */
internal fun roomInfoRoute(state: RoomState): String = "Encrypted · " + when {
    state.anonymous -> "onion relays"
    state.route.nearby && !state.route.internet -> "nearby Bluetooth"
    else -> "public relays"
}

/** A member's name as their tile shows it, and "Ada (you)" for this person. */
internal fun memberName(state: RoomState, tile: ParticipantTile): String {
    val name = ownName(tile, state.profiles, tile.participant)
    if (tile.isSelf) return if (name != null) "$name (you)" else "You"
    // Never a key for somebody with no name (design-calm-shell C7); a key only tells two of the same name apart.
    return name?.let { keyedName(it, tile.participant, sameNamed(state.tiles, state.profiles)) } ?: NAMELESS
}

/**
 * Room info. [page] is where it is open to; back goes to the first page,
 * then closes. The slots are the room screen's existing controls for each
 * subpage, and [authority] the room authority's own tools at the foot.
 */
@Composable
internal fun RoomInfo(
    state: RoomState,
    page: RoomInfoPage,
    onPage: (RoomInfoPage) -> Unit,
    onClose: () -> Unit,
    callLabel: String?,
    onCall: () -> Unit,
    onInvite: (() -> Unit)?,
    onSearch: () -> Unit,
    onBoard: (() -> Unit)?,
    boardNeedsYou: Int,
    canRename: Boolean,
    onMessagePrivately: ((String) -> Unit)?,
    onAddDevice: (() -> Unit)?,
    onOpenCards: (() -> Unit)?,
    onLeave: () -> Unit,
    invite: @Composable () -> Unit,
    privacy: @Composable () -> Unit,
    name: @Composable () -> Unit,
    authority: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (page == RoomInfoPage.MAIN) onClose() else onPage(RoomInfoPage.MAIN) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, if (page == RoomInfoPage.MAIN) "Close room info" else "Back")
            }
            Text(page.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        }
        // The invite page scrolls itself, so it takes the space as it is.
        if (page == RoomInfoPage.INVITE) Box(Modifier.fillMaxWidth().weight(1f).navigationBarsPadding()) { invite() }
        else Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (page) {
                RoomInfoPage.INVITE -> Unit
                RoomInfoPage.PRIVACY -> privacy()
                RoomInfoPage.NAME -> name()
                RoomInfoPage.MAIN -> MainPage(state, onPage, callLabel, onCall, onInvite, onSearch, onBoard, boardNeedsYou, canRename,
                    onMessagePrivately, onAddDevice, onOpenCards, onLeave, authority)
            }
        }
    }
}

@Composable
private fun MainPage(
    state: RoomState,
    onPage: (RoomInfoPage) -> Unit,
    callLabel: String?,
    onCall: () -> Unit,
    onInvite: (() -> Unit)?,
    onSearch: () -> Unit,
    onBoard: (() -> Unit)?,
    boardNeedsYou: Int,
    canRename: Boolean,
    onMessagePrivately: ((String) -> Unit)?,
    onAddDevice: (() -> Unit)?,
    onOpenCards: (() -> Unit)?,
    onLeave: () -> Unit,
    authority: @Composable () -> Unit,
) {
    // Who: the room's picture, name, who is here and who may join.
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!state.privateConversation && !state.quiet && !state.anonymous) PrivateLogo(state.logo ?: state.projectBrand?.image, roomTitle(state), Modifier.size(72.dp))
        Text(roomTitle(state), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(roomInfoWho(state), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        val ends = roomInfoEnds(state.endsAt?.takeIf { !state.conferenceEnded }, state.destruct)
        if (ends.isNotEmpty()) Text(ends, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (callLabel != null) QuickAction(Icons.Outlined.Call, callLabel, onCall)
        if (onInvite != null) QuickAction(Icons.Outlined.PersonAdd, "Invite", onInvite)
        QuickAction(Icons.Filled.Search, "Search", onSearch)
    }

    InfoHeading("Members")
    // People first, then agents, as the web lists them.
    val ordered = state.tiles.filter { it.participant !in state.agentParticipants } + state.tiles.filter { it.participant in state.agentParticipants }
    for (tile in ordered) {
        val name = memberName(state, tile)
        val agent = tile.participant in state.agentParticipants
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProfileAvatar(tile.participant, name, state.profiles[tile.participant], Modifier.size(40.dp))
            // The name and its badge share one weighted slot. A weighted name beside a weighted
            // spacer got half the spare width, so "TheCryptoDonkey (you)" broke mid-word.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge)
                if (agent) Text("Agent", Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            if (onMessagePrivately != null && tile.participant in state.privateConversationPeers) IconButton(onClick = { onMessagePrivately(tile.participant) }, enabled = !state.privateConversationBusy) {
                Icon(Icons.AutoMirrored.Outlined.Chat, "Message $name privately")
            }
        }
    }

    InfoHeading("This room")
    if (onBoard != null) InfoRow(Icons.Outlined.ViewKanban, "Board", if (boardNeedsYou > 0) "$boardNeedsYou need${if (boardNeedsYou == 1) "s" else ""} you" else "", onBoard)
    if (onInvite != null) InfoRow(Icons.Outlined.Link, RoomInfoPage.INVITE.title, "") { onPage(RoomInfoPage.INVITE) }
    InfoRow(Icons.Outlined.Lock, RoomInfoPage.PRIVACY.title, roomInfoRoute(state)) { onPage(RoomInfoPage.PRIVACY) }
    if (canRename) InfoRow(Icons.Outlined.Edit, RoomInfoPage.NAME.title, "Anyone here can change them") { onPage(RoomInfoPage.NAME) }

    if (onAddDevice != null || onOpenCards != null || state.secondary) {
        InfoHeading("You")
        if (state.secondary) Text("You are here as another of your own devices.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
        if (onAddDevice != null) InfoRow(Icons.Outlined.Devices, "Use another device here too", if (state.pairingWaiting) "Waiting for your signer…" else "", onAddDevice)
        if (onOpenCards != null) InfoRow(Icons.Outlined.Badge, "Contact cards", "", onOpenCards)
    }

    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    TextButton(onClick = onLeave, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(if (state.privateConversation) "Leave private chat…" else "Leave room…", color = MaterialTheme.colorScheme.error)
    }
    authority()

    val uri = LocalUriHandler.current
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text("Something unsafe or illegal?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { uri.openUri(REPORT_ABUSE_URL) }) { Text("Report abuse") }
    }
}

/** Where a report goes, the same page the web and the site link to. */
internal const val REPORT_ABUSE_URL = "https://kithmoot.forgesworn.dev/report/"

/** One of Room info's quick actions: a tile, its picture over its word. */
@Composable
private fun RowScope.QuickAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.weight(1f).heightIn(min = 64.dp), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun InfoHeading(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A row that opens a subpage or does one thing: its title, a line under it, and ›. */
@Composable
private fun InfoRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
