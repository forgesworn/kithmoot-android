package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.media.CallRelayStore
import dev.forgesworn.kithmoot.media.CallStunStore
import dev.forgesworn.kithmoot.media.CallIceServers
import dev.forgesworn.kithmoot.media.SpryPairingStore
import dev.forgesworn.kithmoot.media.SpryRoomAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What Settings knows about how this phone's calls connect, read from the
 * stores and the two panels. It is re-read when a relay server is saved, the
 * built-in relay changes, or the page is opened again; nothing is fetched.
 */
@Composable
internal fun rememberCallPathView(callRelay: CallRelayPanel?, publicCallRelays: PublicCallRelaysPanel?, accountKey: String?, hideIp: Boolean): CallPathView {
    val context = LocalContext.current.applicationContext
    val saves by CallRelayStore.saves.collectAsState()
    val failedAt by SpryRoomAccess.ownFailedAt.collectAsState()
    val relayFacts = callRelay?.let { rememberRelayFacts(it, accountKey) }
    val relayPage = relayFacts?.let { CallRelayWords.page(it) }
    val publicView = publicCallRelays?.let { panel ->
        LaunchedEffect(panel) { panel.peek() }
        panel.view.collectAsState().value
    }
    var stored by remember { mutableStateOf(StoredFacts()) }
    // The relay's own phase is a key: starting or stopping it changes whether a hand pairing is read as refused.
    LaunchedEffect(accountKey, saves, relayFacts?.state?.phase) {
        stored = withContext(Dispatchers.IO) { readStored(context, accountKey) }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(failedAt) { now = System.currentTimeMillis() }
    // Its pairing exists only while it runs, and never beside a hand pairing (the relay refuses to start then).
    val running = relayFacts != null && relayPage?.running == true && !relayFacts.handPaired
    return CallPathWords.view(CallPathFacts(
        builtInRow = relayPage?.rowSummary,
        builtInRunning = running,
        spry = stored.spry,
        spryFailedAt = failedAt,
        server = stored.server,
        serverHost = stored.serverHost,
        publicMode = publicView?.mode ?: dev.forgesworn.kithmoot.media.PublicCallRelayMode.OFF,
        publicAnonymous = publicView?.anonymous == true,
        publicOutranked = publicView?.outranked == true,
        publicChosen = publicView?.selected != null,
        hideIp = hideIp,
        stunHost = stored.stunHost,
        stunUnreadable = stored.stunUnreadable,
        nowMs = now,
    ))
}

private data class StoredFacts(
    val spry: StoredKind = StoredKind.NONE,
    val server: StoredKind = StoredKind.NONE,
    val serverHost: String? = null,
    val stunHost: String? = null,
    val stunUnreadable: Boolean = false,
)

private fun readStored(context: android.content.Context, account: String?): StoredFacts {
    var facts = StoredFacts()
    facts = try {
        facts.copy(spry = if (SpryPairingStore(context).read(account) != null) StoredKind.SAVED else StoredKind.NONE)
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { facts.copy(spry = StoredKind.UNREADABLE) }
    facts = try {
        val relay = CallRelayStore(context).read()
        facts.copy(server = if (relay != null) StoredKind.SAVED else StoredKind.NONE, serverHost = relay?.urls?.firstOrNull()?.let(CallPathWords::hostOf))
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { facts.copy(server = StoredKind.UNREADABLE) }
    return try {
        val own = CallStunStore(context).read()
        val defaults = CallIceServers.DEFAULT_STUN_URLS
        val host = when {
            own != null -> own.urls.firstOrNull()?.let(CallPathWords::hostOf)
            defaults == listOf(CallIceServers.CLOUDFLARE_STUN) -> null
            else -> defaults.firstOrNull()?.let(CallPathWords::hostOf) ?: "none"
        }
        facts.copy(stunHost = host)
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { facts.copy(stunUnreadable = true) }
}

/**
 * Calls: how this phone's calls connect, said first and in one place; then
 * the relays that can carry them, Hide my IP, and what rooms bring themselves.
 */
@Composable
internal fun CallsPage(
    view: CallPathView,
    hideIpOnCalls: Boolean,
    onHideIpOnCalls: (Boolean) -> Unit,
    /** Whether the public call relays row is offered (it is not before the panel exists). */
    publicShown: Boolean,
    onOpen: (SettingsPage) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = RowHorizontal, end = RowHorizontal, top = 12.dp, bottom = 4.dp)) {
        Text(view.head, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite; heading() })
        Text(view.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
        view.spryFailed?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
        }
    }
    SettingsSection(CallPathWords.SECTION) {
        view.builtInRow?.let { SettingsNavRow(CallRelayWords.ROW, it) { onOpen(SettingsPage.CALL_RELAY) } }
        SettingsNavRow(CallPathWords.ROW_SPRY, view.spryRow) { onOpen(SettingsPage.SPRY) }
        SettingsNavRow(CallPathWords.ROW_SERVER, view.serverRow) { onOpen(SettingsPage.RELAY_SERVER) }
        if (publicShown) SettingsNavRow(CallPathWords.ROW_PUBLIC, view.publicRow) { onOpen(SettingsPage.PUBLIC_RELAYS) }
    }
    SettingsSection(null, Modifier.padding(top = 16.dp)) {
        SettingsSwitchRow(CallPathWords.HIDE_IP, CallPathWords.HIDE_IP_NOTE, checked = hideIpOnCalls, onCheckedChange = onHideIpOnCalls)
        SettingsNote(CallPathWords.ROOMS_NOTE)
        SettingsNavRow(CallPathWords.ADVANCED, view.advancedRow) { onOpen(SettingsPage.CALL_ADVANCED) }
    }
}
