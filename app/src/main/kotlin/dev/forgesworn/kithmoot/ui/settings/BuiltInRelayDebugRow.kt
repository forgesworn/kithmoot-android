package dev.forgesworn.kithmoot.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.forgesworn.kithmoot.callrelay.RelayPhase
import dev.forgesworn.kithmoot.callrelay.RelayState
import kotlinx.coroutines.flow.StateFlow

/**
 * Debug builds only: a way to start, stop and remove the built-in call relay
 * (stage A4) before its settings screen (stage A5) has had the owner's UI
 * review. Never shown in a release build.
 */
class BuiltInRelayDebug internal constructor(
    internal val state: StateFlow<RelayState?>,
    internal val start: () -> Unit,
    internal val stop: () -> Unit,
    internal val remove: () -> Unit,
)

@Composable
internal fun BuiltInRelayDebugSection(debug: BuiltInRelayDebug) {
    val state by debug.state.collectAsState()
    val on = state?.let { it.wanted || it.phase == RelayPhase.STARTING || it.phase == RelayPhase.RUNNING || it.phase == RelayPhase.STOPPING } == true
    SettingsSection("Built-in call relay (debug build)") {
        SettingsSwitchRow("Run the call relay on this phone", builtInRelaySummary(state), checked = on) { turnOn ->
            if (turnOn) debug.start() else debug.stop()
        }
        SettingsNavRow("Remove the call relay", "Stop it and forget its keys and grants on this phone", onClick = debug.remove)
    }
}

/** A plain account of the relay's state: counts and words only, never an address or key. */
internal fun builtInRelaySummary(state: RelayState?): String {
    state ?: return "Off."
    val head = when (state.phase) {
        RelayPhase.OFF -> "Off."
        RelayPhase.STARTING -> "Starting."
        RelayPhase.RUNNING -> "On. ${state.allocations} in use; ${state.bytesRelayed} bytes relayed since it started."
        RelayPhase.STOPPING -> "Stopping."
        RelayPhase.PAUSED, RelayPhase.CANNOT_HOST, RelayPhase.FAILED -> state.message ?: "Off."
    }
    val parts = mutableListOf(head)
    state.mapping?.let { map ->
        parts += "Router: ${mapOf("pcp" to "PCP", "natpmp" to "NAT-PMP", "upnp" to "UPnP")[map.method]}, ${map.allocationPorts} of ${map.requested} call ports" +
            (if (map.permanent) " (permanent: a crash may leave them open until the relay next starts)" else "") + "."
    }
    state.controlConnected?.let { parts += if (it) "Reachable over your Nostr relays." else "Not connected to your Nostr relays yet." }
    state.refusal?.let { parts += "Node: $it" }
    parts += "Relayed today: ${state.todayBytes} of ${state.dailyLimit} bytes."
    return parts.joinToString(" ")
}
