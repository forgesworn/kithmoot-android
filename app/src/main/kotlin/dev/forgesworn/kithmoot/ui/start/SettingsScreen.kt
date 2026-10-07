package dev.forgesworn.kithmoot.ui.start

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.relay.RelayChoice
import dev.forgesworn.kithmoot.ui.StartState
import dev.forgesworn.kithmoot.ui.room.ProfileAvatar
import dev.forgesworn.kithmoot.ui.settings.AccountPage
import dev.forgesworn.kithmoot.ui.settings.ConnectionsPage
import dev.forgesworn.kithmoot.ui.settings.DeveloperPage
import dev.forgesworn.kithmoot.ui.settings.DiscardRelayChangesDialog
import dev.forgesworn.kithmoot.ui.settings.DisplayPage
import dev.forgesworn.kithmoot.ui.settings.DmRelaysPage
import dev.forgesworn.kithmoot.ui.settings.NOTIFICATIONS_SUMMARY
import dev.forgesworn.kithmoot.ui.settings.NotificationsPage
import dev.forgesworn.kithmoot.ui.settings.PREVIEW_ACCOUNT_SUMMARY
import dev.forgesworn.kithmoot.ui.settings.PrivacyPage
import dev.forgesworn.kithmoot.ui.settings.ProfilePage
import dev.forgesworn.kithmoot.ui.settings.RelaysPage
import dev.forgesworn.kithmoot.ui.settings.SIGNED_OUT_SUMMARY
import dev.forgesworn.kithmoot.ui.settings.SettingsFrame
import dev.forgesworn.kithmoot.ui.settings.SettingsNavRow
import dev.forgesworn.kithmoot.ui.settings.SettingsPage
import dev.forgesworn.kithmoot.ui.settings.SettingsScroll
import dev.forgesworn.kithmoot.ui.settings.SettingsStaticRow
import dev.forgesworn.kithmoot.ui.settings.accountSummary
import dev.forgesworn.kithmoot.ui.settings.connectionsSummary
import dev.forgesworn.kithmoot.ui.settings.privacySummary
import dev.forgesworn.kithmoot.ui.settings.relayIssueCount
import dev.forgesworn.kithmoot.ui.settings.rememberRelayEditor
import dev.forgesworn.kithmoot.ui.settings.updatesSummary
import dev.forgesworn.kithmoot.ui.theme.LocalTextSizeSetting
import dev.forgesworn.kithmoot.ui.theme.cappedTitleStyle
import dev.forgesworn.kithmoot.update.AppUpdates
import dev.forgesworn.kithmoot.update.UpdateSettings

/**
 * The size at which Settings shows its list and the chosen page side by side.
 * Wide enough, and tall enough that it is not a phone on its side: a landscape
 * phone is over 840 dp wide but about 400 tall, and keeps one centred column.
 */
private const val EXPANDED_WIDTH_DP = 840
private const val EXPANDED_HEIGHT_DP = 480
private val ListPaneWidth = 360.dp

/**
 * Settings, full screen. A list of what can be changed (account, notifications
 * and calls, display, privacy, connections, updates), each opening its own
 * page: stacked on a phone, list and page side by side from 840 dp wide. The
 * page is hoisted ([page], [onPageChange]) so it survives rotation and a trip
 * to the developer pages.
 */
@Composable
fun SettingsScreen(
    state: StartState,
    signIn: AccountActions,
    accountSettings: AccountSettingsActions,
    accountRooms: AccountRoomActions,
    relayChoices: List<RelayChoice>,
    onWebAppAddressChanged: (String) -> Boolean,
    /** The notifications body, with its notice first: the Notifications and calls page hosts it. */
    notificationSettings: @Composable () -> Unit,
    page: SettingsPage,
    onPageChange: (SettingsPage) -> Unit,
    onBack: () -> Unit,
    /** What is wrong with notifications and calls, for the list row; null when nothing is. */
    notificationsAttention: String? = null,
    /** Device-wide choices Settings changes while no room is open. */
    onPublicProfiles: (Boolean) -> Unit = {},
    onMirrorSelf: (Boolean) -> Unit = {},
    /** P3-03b-2: null hides the row. */
    onRestoreWitness: (() -> Unit)? = null,
    onVmlsBoxes: (() -> Unit)? = null,
    /** In-app updates: null hides the Updates row. */
    updates: AppUpdates? = null,
) {
    val config = LocalConfiguration.current
    val expanded = config.screenWidthDp >= EXPANDED_WIDTH_DP && config.screenHeightDp >= EXPANDED_HEIGHT_DP
    val signedIn = state.account != null
    val developer = onRestoreWitness != null || onVmlsBoxes != null
    val issues = relayIssueCount(state.relayHealth.values)
    val accountSaved = state.account?.let { account -> state.savedRooms.filter { it.account == account.pubkey } }.orEmpty()
    val importableCount = accountSaved.count { saved -> state.roomBookmarks.rooms.none { it.roomId == saved.id } }
    val editor = rememberRelayEditor(relayChoices, state.account?.pubkey)
    var choosing by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var focusPane by remember { mutableStateOf(false) }

    // A wide screen opens on a page rather than an empty pane.
    val shown = if (page == SettingsPage.ROOT && expanded) (if (signedIn) SettingsPage.ACCOUNT else SettingsPage.NOTIFICATIONS) else page

    // Unsaved relay edits are not thrown away without a question, however the person leaves the list.
    fun guarded(action: () -> Unit) { if (shown == SettingsPage.RELAYS && editor.dirty) pending = action else action() }
    fun go(target: SettingsPage) = guarded { onPageChange(target) }
    fun up() {
        val parent = shown.parent
        if (parent == null || (expanded && parent == SettingsPage.ROOT)) guarded(onBack) else go(parent)
    }
    BackHandler(enabled = shown != SettingsPage.ROOT && (!expanded || shown.isThirdLevel)) { up() }

    // A page whose subject has gone (signed out from its own page, say) is not left on screen.
    LaunchedEffect(shown, state.account, state.retainedAccount, updates, developer) {
        val gone = when (shown) {
            SettingsPage.ACCOUNT -> !signedIn && state.retainedAccount == null
            SettingsPage.PROFILE -> !signedIn
            SettingsPage.UPDATES -> updates == null
            SettingsPage.DEVELOPER -> !developer
            else -> false
        }
        if (gone) onPageChange(SettingsPage.ROOT)
        else if (shown == SettingsPage.DM_RELAYS && !signedIn) onPageChange(SettingsPage.CONNECTIONS)
    }
    LaunchedEffect(shown, signedIn) {
        if (signedIn && shown == SettingsPage.PROFILE) accountSettings.loadProfile()
        if (signedIn && shown == SettingsPage.DM_RELAYS) accountSettings.loadDmRelays()
    }
    LaunchedEffect(state.account?.pubkey) { if (state.account != null) choosing = false }

    val root: @Composable () -> Unit = {
        SettingsRoot(
            state, signIn, issues, notificationsAttention, updates, developer, selected = if (expanded) shown.section else null,
            onOpen = { target -> focusPane = expanded; go(target) },
            onSignIn = { signIn.onRefreshSigners(); choosing = true },
        )
    }
    val actions: @Composable RowScope.(SettingsPage) -> Unit = { target ->
        if (target == SettingsPage.RELAYS) TextButton({ editor.save(accountSettings) }, enabled = !state.busy && !state.profileBusy && editor.dirty) { Text("Save") }
    }
    val body: @Composable (SettingsPage) -> Unit = { target ->
        when (target) {
            SettingsPage.ROOT -> Unit
            SettingsPage.ACCOUNT -> AccountPage(state, signIn, accountSettings, accountRooms, importableCount, onEditProfile = { go(SettingsPage.PROFILE) })
            SettingsPage.PROFILE -> ProfilePage(state, accountSettings)
            SettingsPage.NOTIFICATIONS -> NotificationsPage(state.mirrorSelf, onMirrorSelf, notificationSettings)
            SettingsPage.DISPLAY -> DisplayPage()
            SettingsPage.PRIVACY -> PrivacyPage(state.publicProfiles, onPublicProfiles)
            SettingsPage.CONNECTIONS -> ConnectionsPage(issues, signedIn, state.webAppAddress, onWebAppAddressChanged,
                onRelays = { go(SettingsPage.RELAYS) }, onDmRelays = { go(SettingsPage.DM_RELAYS) })
            SettingsPage.RELAYS -> RelaysPage(state, editor, inRoom = false, accountSettings)
            SettingsPage.DM_RELAYS -> DmRelaysPage(state, accountSettings)
            SettingsPage.UPDATES -> updates?.let { UpdateSettings(it) }
            SettingsPage.DEVELOPER -> DeveloperPage(onRestoreWitness, onVmlsBoxes)
        }
    }
    val fade = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) }

    if (!expanded) {
        AnimatedContent(shown, transitionSpec = { fade() }, label = "settings page") { target ->
            if (target == SettingsPage.ROOT) SettingsFrame("Settings", onBack = ::up) { root() }
            else SettingsFrame(settingsTitle(target), onBack = ::up, actions = { actions(target) }) { body(target) }
        }
    } else {
        ExpandedSettings(
            list = { root() },
            pane = {
                AnimatedContent(shown, transitionSpec = { fade() }, label = "settings page") { target ->
                    PaneContent(settingsTitle(target), back = if (target.isThirdLevel) ({ up() }) else null, focus = focusPane, actions = { actions(target) }) { body(target) }
                }
            },
            onBack = { guarded(onBack) },
        )
    }

    if (choosing) SignInChoicesSheet(state, signIn) { choosing = false }
    if (pending != null) DiscardRelayChangesDialog(
        onKeep = { pending = null },
        onDiscard = { editor.discard(); val next = pending; pending = null; next?.invoke() },
    )
}

internal fun settingsTitle(page: SettingsPage): String = when (page) {
    SettingsPage.ROOT -> "Settings"
    SettingsPage.ACCOUNT -> "Account"
    SettingsPage.PROFILE -> "Edit public profile"
    SettingsPage.NOTIFICATIONS -> "Notifications and calls"
    SettingsPage.DISPLAY -> "Display"
    SettingsPage.PRIVACY -> "Privacy"
    SettingsPage.CONNECTIONS -> "Connections"
    SettingsPage.RELAYS -> "Relays"
    SettingsPage.DM_RELAYS -> "Relays for private conversations"
    SettingsPage.UPDATES -> "Updates"
    SettingsPage.DEVELOPER -> "Your Bothy box (preview)"
}

/** The root list: who you are, then one row for each page. */
@Composable
private fun SettingsRoot(
    state: StartState,
    signIn: AccountActions,
    issues: Int,
    notificationsAttention: String?,
    updates: AppUpdates?,
    developer: Boolean,
    selected: SettingsPage?,
    onOpen: (SettingsPage) -> Unit,
    onSignIn: () -> Unit,
) {
    val account = state.account
    val retained = state.retainedAccount
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        when {
            account != null -> SettingsNavRow(
                account.shownName, accountSummary(account, state.roomBookmarks.error, state.roomBookmarks.pending),
                leading = { ProfileAvatar(account.pubkey, account.name, account.profile, Modifier.size(40.dp)) },
                selected = selected == SettingsPage.ACCOUNT, enabled = !state.busy,
            ) { onOpen(SettingsPage.ACCOUNT) }
            state.signingIn -> SettingsStaticRow(
                "Sign in with Nostr", "Waiting for your signer…",
                leading = { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) },
                trailing = { TextButton(signIn.onCancelSignIn) { Text("Cancel") } },
            )
            retained != null -> SettingsNavRow(
                "Preview account", PREVIEW_ACCOUNT_SUMMARY,
                leading = { ProfileAvatar(retained.pubkey, retained.name, retained.profile, Modifier.size(40.dp)) },
                selected = selected == SettingsPage.ACCOUNT, enabled = !state.busy,
            ) { onOpen(SettingsPage.ACCOUNT) }
            else -> SettingsNavRow("Sign in with Nostr", SIGNED_OUT_SUMMARY, icon = Icons.Outlined.AccountCircle,
                enabled = !state.busy, onClick = onSignIn)
        }
        state.signInError?.let { error ->
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                TextButton(signIn.onDismissError) { Text("Dismiss") }
            }
        }

        SettingsNavRow("Notifications and calls", notificationsAttention ?: NOTIFICATIONS_SUMMARY,
            icon = if (notificationsAttention != null) Icons.Outlined.ErrorOutline else Icons.Outlined.Notifications,
            selected = selected == SettingsPage.NOTIFICATIONS) { onOpen(SettingsPage.NOTIFICATIONS) }
        SettingsNavRow("Display", "Text size: ${LocalTextSizeSetting.current.size.label}", icon = Icons.Outlined.FormatSize,
            selected = selected == SettingsPage.DISPLAY) { onOpen(SettingsPage.DISPLAY) }
        SettingsNavRow("Privacy", privacySummary(state.publicProfiles), icon = Icons.Outlined.Lock,
            selected = selected == SettingsPage.PRIVACY) { onOpen(SettingsPage.PRIVACY) }
        SettingsNavRow("Connections", connectionsSummary(issues), icon = Icons.Outlined.Hub,
            selected = selected == SettingsPage.CONNECTIONS) { onOpen(SettingsPage.CONNECTIONS) }
        updates?.let {
            val updateState by it.state.collectAsState()
            SettingsNavRow("Updates", updatesSummary(it.versionName, updateState, it.installedFrom), icon = Icons.Outlined.SystemUpdate,
                selected = selected == SettingsPage.UPDATES) { onOpen(SettingsPage.UPDATES) }
        }
        if (developer) SettingsNavRow("Your Bothy box (preview)", "VMLS rooms and the restore witness", icon = Icons.Outlined.Code,
            selected = selected == SettingsPage.DEVELOPER) { onOpen(SettingsPage.DEVELOPER) }
    }
}

/** List and page side by side: the list fixed at 360 dp, the page filling the rest. Lists read before pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExpandedSettings(list: @Composable () -> Unit, pane: @Composable () -> Unit, onBack: () -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = cappedTitleStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.width(ListPaneWidth).fillMaxHeight().verticalScroll(rememberScrollState()).semantics { isTraversalGroup = true; traversalIndex = 0f }) { list() }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f).fillMaxHeight().semantics { isTraversalGroup = true; traversalIndex = 1f }) { pane() }
        }
    }
}

/** A page in the wide layout: its title as a heading (no second app bar), a way back for third-level pages, the page's action. */
@Composable
private fun PaneContent(title: String, back: (() -> Unit)?, focus: Boolean, actions: @Composable RowScope.() -> Unit, content: @Composable () -> Unit) {
    val headingFocus = remember { FocusRequester() }
    LaunchedEffect(title) { if (focus) runCatching { headingFocus.requestFocus() } }
    SettingsScroll(centred = false) {
        Row(Modifier.fillMaxWidth().padding(start = if (back == null) 24.dp else 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (back != null) IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(title, style = cappedTitleStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).focusRequester(headingFocus).focusable().semantics { heading() })
            actions()
        }
        content()
    }
}
