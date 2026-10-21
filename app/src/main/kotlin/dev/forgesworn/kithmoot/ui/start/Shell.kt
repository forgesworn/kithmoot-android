package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewKanban
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewKanban
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.R

/**
 * The places the app is organised into (design-calm-shell section 5.1):
 * Chats, People and Boards, with Settings beside them. One vocabulary on all
 * three apps, so the labels here are the web's word for word.
 */
enum class Destination(val label: String) { CHATS("Chats"), PEOPLE("People"), BOARDS("Boards") }

/**
 * Which destinations the navigation offers. People needs a Nostr sign-in
 * (Q14: hidden, not explained, when signed out). A Tor-only profile has no
 * account and no cross-room activity, so it offers Chats alone, as the old
 * home row of Contacts, Inbox and All work did.
 */
internal fun destinationsFor(signedIn: Boolean, anonymous: Boolean): List<Destination> = when {
    anonymous -> listOf(Destination.CHATS)
    signedIn -> listOf(Destination.CHATS, Destination.PEOPLE, Destination.BOARDS)
    else -> listOf(Destination.CHATS, Destination.BOARDS)
}

/** "3 unread" for the badge's spoken form, so a number is never read bare. */
internal fun badgeDescription(destination: Destination, count: Int): String? = when {
    count <= 0 -> null
    destination == Destination.BOARDS -> "$count need you"
    else -> "$count unread"
}

private fun Destination.icon(selected: Boolean): ImageVector = when (this) {
    Destination.CHATS -> if (selected) Icons.Filled.Forum else Icons.Outlined.Forum
    Destination.PEOPLE -> if (selected) Icons.Filled.People else Icons.Outlined.People
    Destination.BOARDS -> if (selected) Icons.Filled.ViewKanban else Icons.Outlined.ViewKanban
}

@Composable
private fun DestinationIcon(destination: Destination, selected: Boolean, count: Int) {
    if (count > 0) BadgedBox(badge = {
        Badge(Modifier.semantics { badgeDescription(destination, count)?.let { contentDescription = it } },
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
            // Capped like the bars' titles, so at large text the count does not cover its icon.
            val style = MaterialTheme.typography.labelSmall
            val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
            Text(if (count > 99) "99+" else count.toString(), modifier = Modifier.clearAndSetSemantics {},
                style = style.copy(fontSize = style.fontSize * (minOf(scale, 1.3f) / scale)))
        }
    }) { Icon(destination.icon(selected), null) }
    else Icon(destination.icon(selected), null)
}

/** The bottom bar, at the list level on compact widths only; a room covers it. */
@Composable
fun ShellNavigationBar(
    destinations: List<Destination>,
    current: Destination?,
    onSelect: (Destination) -> Unit,
    badges: Map<Destination, Int> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        // Keyed, so an item keeps its node when People appears at sign-in:
        // TalkBack and tests holding the Boards item still reach Boards.
        for (destination in destinations) key(destination) {
            NavigationBarItem(
                selected = destination == current,
                onClick = { onSelect(destination) },
                icon = { DestinationIcon(destination, destination == current, badges[destination] ?: 0) },
                label = { Text(destination.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                alwaysShowLabel = true,
            )
        }
    }
}

/**
 * The rail, at medium and expanded widths, beside the list or the room: the
 * logo (decorative), the destinations, then Settings and the account at the
 * foot, as on the web and desktop.
 */
@Composable
fun ShellNavigationRail(
    destinations: List<Destination>,
    current: Destination?,
    onSelect: (Destination) -> Unit,
    settingsSelected: Boolean,
    onSettings: () -> Unit,
    badges: Map<Destination, Int> = emptyMap(),
    account: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    NavigationRail(modifier, containerColor = MaterialTheme.colorScheme.surfaceContainer, header = {
        Image(painterResource(R.drawable.brand_artwork), null, Modifier.padding(top = 8.dp, bottom = 8.dp).size(36.dp))
    }) {
        for (destination in destinations) key(destination) {
            NavigationRailItem(
                selected = destination == current,
                onClick = { onSelect(destination) },
                icon = { DestinationIcon(destination, destination == current, badges[destination] ?: 0) },
                label = { Text(destination.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                alwaysShowLabel = true,
            )
        }
        Spacer(Modifier.weight(1f))
        NavigationRailItem(
            selected = settingsSelected,
            onClick = onSettings,
            icon = { Icon(if (settingsSelected) Icons.Filled.Settings else Icons.Outlined.Settings, null) },
            label = { Text("Settings", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            alwaysShowLabel = true,
        )
        if (account != null) Column(Modifier.padding(vertical = 8.dp)) { account() }
    }
}

/**
 * The counts on the destinations: Chats counts the chats with something
 * unread, as Signal does, leaving out muted ones. Boards has no count until its readers have run,
 * and they run only while Boards is open, so it shows none rather than
 * opening every room's work to fill a badge.
 */
internal fun shellBadges(state: dev.forgesworn.kithmoot.ui.StartState): Map<Destination, Int> =
    mapOf(Destination.CHATS to state.unreadCounts.count { (id, count) -> count > 0 && id !in state.mutedRooms })

/**
 * The account at the end of the Chats bar (compact) or the foot of the rail:
 * the avatar, or a plain person outline when signed out. Its menu is Settings
 * and Sign in or Sign out, nothing else (design-calm-shell 9.5).
 */
@Composable
fun ShellAccountButton(
    account: dev.forgesworn.kithmoot.ui.AccountView?,
    showPicture: Boolean,
    busy: Boolean,
    onSettings: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    inRoom: Boolean = false,
) {
    var menu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var leaving by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        androidx.compose.material3.IconButton({ menu = true }, Modifier.size(48.dp).semantics {
            contentDescription = if (account != null) "Account menu for ${account.shownName}" else "Account menu"
        }) {
            if (account != null) dev.forgesworn.kithmoot.ui.room.ProfileAvatar(account.pubkey, account.shownName,
                account.profile.takeIf { showPicture }, Modifier.size(32.dp).clearAndSetSemantics {})
            else Icon(androidx.compose.material.icons.Icons.Outlined.AccountCircle, null)
        }
        androidx.compose.material3.DropdownMenu(menu, { menu = false }) {
            androidx.compose.material3.DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; onSettings() })
            if (account == null) androidx.compose.material3.DropdownMenuItem(text = { Text("Sign in") }, onClick = { menu = false; onSignIn() })
            else androidx.compose.material3.DropdownMenuItem(text = { Text("Sign out") }, enabled = !busy, onClick = { menu = false; leaving = true })
        }
    }
    if (leaving) SignOutDialog(inRoom, onConfirm = { leaving = false; onSignOut() }, onDismiss = { leaving = false })
}
