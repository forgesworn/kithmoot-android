package dev.forgesworn.kithmoot.ui.settings

import android.app.NotificationManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.forgesworn.kithmoot.callrelay.CallRelayService
import dev.forgesworn.kithmoot.callrelay.RelayRefusal
import dev.forgesworn.kithmoot.callrelay.RelayState
import dev.forgesworn.kithmoot.media.CallRelayStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * The built-in call relay as the settings screens see it (settings spec,
 * stage A5): what it is doing, why a Turn on was refused, and the three
 * things a person can ask of it. [preview] and [setPreview] exist only in a
 * debug build, for the Developer page's "Call relay screen preview".
 */
class CallRelayPanel internal constructor(
    internal val state: StateFlow<RelayState?>,
    internal val lastUsedAt: StateFlow<Long?>,
    internal val refusal: StateFlow<RelayRefusal?>,
    /** Whether a Spry node was paired by hand for the account: that outranks the built-in relay. */
    internal val readHandPaired: suspend (String?) -> Boolean,
    internal val turnOn: () -> Unit,
    internal val stop: () -> Unit,
    internal val removeKeys: () -> Unit,
    internal val preview: StateFlow<String?>? = null,
    internal val setPreview: ((String?) -> Unit)? = null,
)

/** What the page is a function of now, re-read when the relay speaks, a minute passes or the screen returns. */
@Composable
internal fun rememberRelayFacts(panel: CallRelayPanel, accountKey: String?): RelayFacts {
    val state by panel.state.collectAsState()
    val lastUsed by panel.lastUsedAt.collectAsState()
    val refusal by panel.refusal.collectAsState()
    val previewId = panel.preview?.collectAsState()?.value
    var handPaired by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // A pairing made or removed on the Spry dialog is found when the screen returns.
    var resumed by remember { mutableLongStateOf(0L) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { resumed++; now = System.currentTimeMillis() } }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(accountKey, resumed, state?.phase) { handPaired = panel.readHandPaired(accountKey) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    return previewId?.let { CallRelayPreviews.facts(it, now) } ?: RelayFacts(state, refusal, handPaired, lastUsed, now)
}

/** Whether Android will show the relay's notification: false when the app, or the relay's channel, is blocked. */
@Composable
private fun rememberNotificationsOff(): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun off(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return true
        val channel = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CallRelayService.CHANNEL_ID)
        return channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE
    }
    var blocked by remember { mutableStateOf(off()) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) blocked = off() }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    return blocked
}

/** Settings, Calls, Call relay on this phone. Built only from the settings kit. */
@Composable
internal fun CallRelayPage(panel: CallRelayPanel, accountKey: String?, hideIpOnCalls: Boolean) {
    val facts = rememberRelayFacts(panel, accountKey)
    val page = CallRelayWords.page(facts)
    val notificationsOff = rememberNotificationsOff()
    var asking by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf(false) }
    val wanted = page.switch == RelaySwitch.ON || page.switch == RelaySwitch.STOPPING
    // With a relay server saved it carries this phone's own calls first; the relay still serves the rooms (T-OWNCALLS).
    val context = LocalContext.current.applicationContext
    val saves by CallRelayStore.saves.collectAsState()
    var serverSaved by remember { mutableStateOf(false) }
    LaunchedEffect(saves) { serverSaved = runCatching { withContext(Dispatchers.IO) { CallRelayStore(context).read() != null } }.getOrDefault(true) }

    SettingsSection(null) {
        SettingsSwitchRow(CallRelayWords.SWITCH, checked = wanted, enabled = page.switch == RelaySwitch.ON || page.switch == RelaySwitch.OFF) { turnOn ->
            if (turnOn) asking = true else panel.stop()
        }
        if (page.off) {
            SettingsStaticRow(page.headline, live = true)
            SettingsNote(page.line2.orEmpty())
            SettingsNote(page.line3.orEmpty())
        } else if (page.running) {
            SettingsStaticRow(page.headline, page.line2, live = true)
            SettingsStaticRow(page.useTitle.orEmpty(), page.useSummary)
        } else {
            SettingsStaticRow(page.headline, page.line2, live = true)
            page.line3?.let { SettingsNote(it) }
        }
        if (page.offerRemoveKeys) SettingsActionRow(CallRelayWords.REMOVE_KEYS, destructive = true) { removing = true }
        if (wanted && notificationsOff) SettingsNote(CallRelayWords.NO_NOTIFICATIONS, live = true)
        if (page.running) {
            val details = CallRelayWords.details(facts)
            if (details.isNotEmpty()) SettingsDisclosure(CallRelayWords.DETAILS) { for (line in details) SettingsNote(line) }
            SettingsNote(CallRelayWords.NOTE_ON)
            if (serverSaved) SettingsNote(CallPathWords.OWN_CALLS)
        }
    }

    if (asking) TurnOnDialog(hideIpOnCalls, onCancel = { asking = false }, onConfirm = { asking = false; panel.turnOn() })
    if (removing) AlertDialog(
        onDismissRequest = { removing = false },
        title = { Text(CallRelayWords.REMOVE_KEYS_TITLE) },
        text = { Text(CallRelayWords.REMOVE_KEYS_MESSAGE) },
        dismissButton = { TextButton({ removing = false }) { Text(CallRelayWords.ON_CANCEL) } },
        confirmButton = { TextButton({ removing = false; panel.removeKeys() }) { Text(CallRelayWords.REMOVE_KEYS_CONFIRM, color = MaterialTheme.colorScheme.error) } },
    )
}

/** The question asked on every Turn on (spec 7.1): both buttons are plain text buttons, so nothing pushes Turn on. */
@Composable
private fun TurnOnDialog(hideIpOnCalls: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(CallRelayWords.ON_TITLE) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(CallRelayWords.onNotice(), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Text(CallRelayWords.ON_PHONE, style = MaterialTheme.typography.bodyMedium)
                if (hideIpOnCalls) {
                    Spacer(Modifier.height(12.dp))
                    Text(CallRelayWords.ON_HIDE_IP, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        dismissButton = { TextButton(onCancel) { Text(CallRelayWords.ON_CANCEL) } },
        confirmButton = { TextButton(onConfirm) { Text(CallRelayWords.ON_CONFIRM) } },
    )
}
