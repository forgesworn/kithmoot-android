package dev.forgesworn.kithmoot.ui.settings

import dev.forgesworn.kithmoot.callrelay.CANNOT_HOST_TEXT
import dev.forgesworn.kithmoot.callrelay.CANNOT_HOST_UNKNOWN
import dev.forgesworn.kithmoot.callrelay.RelayCaps
import dev.forgesworn.kithmoot.callrelay.RelayFailure
import dev.forgesworn.kithmoot.callrelay.RelayGate
import dev.forgesworn.kithmoot.callrelay.RelayPhase
import dev.forgesworn.kithmoot.callrelay.RelayRefusal
import dev.forgesworn.kithmoot.callrelay.RelayState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the page is a function of: the relay's state and why a Turn on was
 * refused before the relay process had it. The page never reads anything
 * else, so every screen is reachable from here (the Developer page's preview
 * builds these by hand).
 *
 * There is no outside check: Settings leaves any open room, so the spec's
 * "check from a device in the room you have open" cannot be offered on a phone.
 */
internal data class RelayFacts(
    val state: RelayState? = null,
    val refusal: RelayRefusal? = null,
    /** A Spry node was paired by hand for this account: the built-in relay stays off. */
    val handPaired: Boolean = false,
    /** When the last connection through the relay ended this run, epoch milliseconds. */
    val lastUsedAt: Long? = null,
    val nowMs: Long,
    val zone: ZoneId = ZoneId.systemDefault(),
)

/** What the switch shows and whether it answers. */
internal enum class RelaySwitch { OFF, ON, STOPPING, UNAVAILABLE }

/**
 * The page as words (settings spec, sections 6 and 9). [headline] is line 1;
 * [line2] and [line3] are the next lines, as the state table gives them. While
 * the relay runs, line 3 is [useTitle] and [useSummary] together and line 2 is
 * empty, or the reason nobody can use it yet. The Reachable line of the spec
 * is not built on Android: see [RelayFacts] and the report of stage A5.
 */
internal data class RelayPage(
    val headline: String,
    val line2: String? = null,
    val line3: String? = null,
    val rowSummary: String,
    val switch: RelaySwitch,
    /** Running: the page shows the reachable and use facts and Details. */
    val running: Boolean = false,
    val useTitle: String? = null,
    val useSummary: String? = null,
    /** The tester note and intro paragraph show, in place of line 2 and 3. */
    val off: Boolean = false,
    /** The saved keys cannot be read: offer to remove them. */
    val offerRemoveKeys: Boolean = false,
)

/**
 * Every word of the call relay page and Turn on dialog, in one
 * place so the owner's final wording lands as one change (the pattern of
 * [dev.forgesworn.kithmoot.ui.InvitationWords]). The relay's own enums
 * ([RelayGate], [RelayFailure], [CANNOT_HOST_TEXT]) hold the sentences the
 * notification shares; this composes the page from them.
 */
internal object CallRelayWords {
    const val ROW = "Call relay on this phone"
    const val TITLE = "Call relay on this phone"
    const val SWITCH = "Use call relay"
    const val DETAILS = "Details"
    const val REMOVE_KEYS = "Remove keys…"
    const val REMOVE_KEYS_TITLE = "Remove the relay's saved keys?"
    const val REMOVE_KEYS_MESSAGE = "The relay starts afresh with new keys. People will use its new address the next time they open your rooms."
    const val REMOVE_KEYS_CONFIRM = "Remove keys"

    const val OFF = "Off"
    const val INTRO = "When two people can't connect directly, a call relay passes their encrypted audio and video along. " +
        "Turn this on to let people in rooms you made use this phone as their relay. It uses your upload bandwidth while " +
        "they're on a call, and they'll see your internet address. You run it, and you can stop it at any time."
    const val TESTER = "A phone is usually more useful as a tester for a relay on your computer. To test one, open the same room " +
        "on this phone with Wi-Fi off, then choose Check from another device on the computer."
    const val NOTE_ON = "People in rooms you made use this relay when they can't connect directly. In other rooms, turn on " +
        "Share my call relay here in the room's Privacy and connection. They see your internet address."
    const val NO_NOTIFICATIONS = "Notifications are off for KithMoot, so Android can't show that the relay is on. You can stop it here."

    const val STARTING = "Starting…"
    const val STARTING_2 = "Setting up your router. This can take up to a minute."
    const val RUNNING = "Running on this phone"
    const val RUNNING_NO_CONTROL = "Running, waiting for your Nostr relays"
    const val RUNNING_NO_CONTROL_2 = "Nobody can use it until KithMoot reaches at least one of your Nostr relays."
    const val WAITING = "Waiting for a network"
    const val WAITING_2 = "It starts when this phone is back online."
    const val CANT = "Can't run on this network"
    const val CANT_RETRY = "It tries again when this phone joins another network."
    const val CRASHED = "The relay stopped unexpectedly"
    const val CRASHED_2 = "Starting it again…"
    const val STOPPED = "Stopped"
    const val CRASHED_TWICE_NEXT = "Turn it on again to try once more. If it keeps stopping, restart KithMoot."
    const val RETRY = "Try turning it on again."
    const val HELPER_NEXT = "Reinstall KithMoot from kithmoot.app, then try again."
    const val KEYS = "The relay's saved keys couldn't be read. This happens after switching between builds of KithMoot."
    const val STOP_SLOW = "Still stopping"
    const val LIMIT = "Paused for today"
    const val LIMIT_3 = "It starts again after midnight."
    const val SPRY = "You already use your own Spry relay"
    const val SPRY_2 = "Your calls go through your Spry node, so this phone's relay stays off. To use this one instead, unpair your Spry node first."
    /** Spec T-NOACCT says "Start or open a room first"; on a phone the relay's account is the Nostr sign-in. */
    const val NO_ACCOUNT = "Sign in with your Nostr account first, so the relay knows whose rooms it serves."
    const val OTHER_ACCOUNT = "This phone's relay is set up for another account you use here. Switch to that account to use it."
    const val NO_CONTROL = "None of your Nostr relays can carry requests for the call relay. Add one whose address starts with wss:// and uses a domain name, in Nostr relays."
    const val STOPPING = "Stopping…"
    const val PAUSED = "Paused"
    const val PAUSED_3 = "It starts again by itself when that changes."

    // The Calls row's second line.
    private const val ROW_ON = "On"
    private const val ROW_STARTING = "Starting…"
    private const val ROW_RESTARTING = "Restarting…"
    private const val ROW_STOPPING = "Stopping…"
    private const val ROW_PAUSED_MIDNIGHT = "Paused until midnight"
    private const val ROW_SPRY = "Not used: you have Spry"

    // The Turn on dialog.
    const val ON_TITLE = "Turn on the call relay on this phone?"
    const val ON_CANCEL = "Cancel"
    const val ON_CONFIRM = "Turn on"
    const val ON_PHONE = "It runs only on Wi-Fi that isn't metered, while this phone is charging and has at least 50% battery, and it uses some battery."
    const val ON_HIDE_IP = "Hide my IP address doesn't hide your address from people who use your relay."

    /** T-ON-NOTICE, as the owner approved it on 30 October 2026. */
    fun onNotice(dailyLimit: Long = RelayCaps.DAILY_LIMIT_BYTES): String =
        "People in rooms you made, and rooms where you share it, can send their encrypted calls through this phone when they can't connect directly. " +
            "They'll see your internet address. It uses your upload bandwidth, up to ${limit(dailyLimit)} a day. " +
            "KithMoot asks your router to open what the relay needs and closes it again when the relay stops. " +
            "You run this relay and can stop it at any time. " +
            "Check that the laws that apply to you and your internet provider's terms allow it."

    /** The shown line of a gate where the short row words are wanted (spec 6.6). */
    fun gateRow(gate: RelayGate): String = when (gate) {
        RelayGate.NO_WIFI -> "Paused · not on Wi-Fi"
        RelayGate.METERED -> "Paused · metered Wi-Fi"
        RelayGate.VPN -> "Paused · VPN on"
        RelayGate.NO_ADDRESS -> "Paused · Wi-Fi connecting"
        RelayGate.NOT_CHARGING -> "Paused · not charging"
        RelayGate.LOW_BATTERY -> "Paused · battery under 50%"
        RelayGate.HOT -> "Paused · phone is warm"
        RelayGate.DAILY_LIMIT -> ROW_PAUSED_MIDNIGHT
    }

    // Formatters.

    /** Decimal units, en-GB: "0 MB", "under 1 MB", "300 MB", "1.2 GB". */
    fun bytes(count: Long): String = when {
        count <= 0 -> "0 MB"
        count < 1_000_000 -> "under 1 MB"
        count < 1_000_000_000 -> "${count / 1_000_000} MB"
        else -> { val tenths = count / 100_000_000; "${tenths / 10}.${tenths % 10} GB" }
    }

    /** A limit as "2 GB" (whole gigabytes where it is one). */
    fun limit(count: Long): String = if (count >= 1_000_000_000 && count % 1_000_000_000 == 0L) "${count / 1_000_000_000} GB" else bytes(count)

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

    /** "at 14:02", or "yesterday at 23:58" when the run crossed midnight. */
    fun atTime(atMs: Long, nowMs: Long, zone: ZoneId): String {
        val moment = Instant.ofEpochMilli(atMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val clock = CLOCK.format(moment)
        return if (moment.toLocalDate().isBefore(today)) "yesterday at $clock" else "at $clock"
    }

    private fun method(code: String) = when (code) { "pcp" -> "PCP"; "natpmp" -> "NAT-PMP"; else -> "UPnP" }

    /** The Details lines, each only where its data exists (spec 6.7). Never an address, endpoint, pairing or key. */
    fun details(facts: RelayFacts): List<String> {
        val state = facts.state ?: return emptyList()
        val lines = mutableListOf<String>()
        state.mapping?.let { map ->
            lines += "Router: opened automatically using ${method(map.method)}"
            lines += "Openings on your router: ${map.allocationPorts} of ${map.requested}"
            if (map.permanent) lines += "Your router only offers permanent openings. If KithMoot crashes they stay open until it next starts, when it removes them."
            if (map.restored > 0) lines += "Your router dropped ${map.restored} ${if (map.restored == 1L) "opening" else "openings"}; the relay put ${if (map.restored == 1L) "it" else "them"} back."
        }
        state.controlConnected?.let { lines += "Nostr relays: " + if (it) "connected" else "not connected yet" }
        lines += "Connections now: ${state.allocations}"
        lines += "Relayed since it started: ${bytes(state.bytesRelayed)}"
        lines += "Daily limit: ${limit(state.dailyLimit)}. When it's reached, the relay pauses until midnight."
        return lines
    }

    /** The page for a state (spec 6.2). */
    fun page(facts: RelayFacts): RelayPage {
        val state = facts.state
        val active = state != null && (state.wanted || state.phase == RelayPhase.STARTING || state.phase == RelayPhase.RUNNING || state.phase == RelayPhase.STOPPING)
        if (!active) {
            if (facts.handPaired) return RelayPage(SPRY, SPRY_2, rowSummary = ROW_SPRY, switch = RelaySwitch.UNAVAILABLE)
            refused(facts.refusal)?.let { return it }
        }
        state ?: return off()
        return when (state.phase) {
            RelayPhase.OFF -> if (state.wanted) RelayPage(WAITING, WAITING_2, rowSummary = WAITING, switch = RelaySwitch.ON) else off()
            RelayPhase.STARTING -> RelayPage(STARTING, STARTING_2, rowSummary = ROW_STARTING, switch = RelaySwitch.ON)
            RelayPhase.RUNNING -> running(facts, state)
            RelayPhase.STOPPING -> RelayPage(STOPPING, rowSummary = ROW_STOPPING, switch = RelaySwitch.STOPPING)
            RelayPhase.PAUSED -> {
                val gate = state.gate ?: RelayGate.NO_WIFI
                if (gate == RelayGate.DAILY_LIMIT) RelayPage(LIMIT, gate.text, LIMIT_3, ROW_PAUSED_MIDNIGHT, RelaySwitch.ON)
                else RelayPage(PAUSED, gate.text, PAUSED_3, gateRow(gate), RelaySwitch.ON)
            }
            RelayPhase.CANNOT_HOST -> {
                val reason = CANNOT_HOST_TEXT[state.reason] ?: CANNOT_HOST_UNKNOWN
                if (state.wanted) RelayPage(CANT, reason, CANT_RETRY, CANT, RelaySwitch.ON) else RelayPage(CANT, reason, rowSummary = CANT, switch = RelaySwitch.OFF)
            }
            RelayPhase.FAILED -> failed(state)
        }
    }

    private fun off() = RelayPage(OFF, INTRO, TESTER, rowSummary = OFF, switch = RelaySwitch.OFF, off = true)

    private fun refused(refusal: RelayRefusal?): RelayPage? = when (refusal) {
        null -> null
        RelayRefusal.HAND_PAIRED -> RelayPage(SPRY, SPRY_2, rowSummary = ROW_SPRY, switch = RelaySwitch.UNAVAILABLE)
        RelayRefusal.NO_ACCOUNT -> RelayPage(STOPPED, NO_ACCOUNT, rowSummary = OFF, switch = RelaySwitch.OFF)
        RelayRefusal.OTHER_ACCOUNT -> RelayPage(STOPPED, OTHER_ACCOUNT, rowSummary = OFF, switch = RelaySwitch.OFF)
        RelayRefusal.NO_CONTROL_RELAYS -> RelayPage(STOPPED, NO_CONTROL, rowSummary = OFF, switch = RelaySwitch.OFF)
        RelayRefusal.KEYS_UNREADABLE -> RelayPage(STOPPED, KEYS, rowSummary = STOPPED, switch = RelaySwitch.OFF, offerRemoveKeys = true)
        RelayRefusal.START_FAILED -> RelayPage(STOPPED, RelayFailure.START_FAILED.text, RETRY, STOPPED, RelaySwitch.OFF)
    }

    private fun failed(state: RelayState): RelayPage = when (state.failure) {
        RelayFailure.CRASHED -> RelayPage(CRASHED, CRASHED_2, rowSummary = ROW_RESTARTING, switch = RelaySwitch.ON)
        RelayFailure.CRASHED_TWICE -> RelayPage(STOPPED, RelayFailure.CRASHED_TWICE.text, CRASHED_TWICE_NEXT, STOPPED, RelaySwitch.OFF)
        RelayFailure.START_TIMEOUT -> RelayPage(STOPPED, RelayFailure.START_TIMEOUT.text, RETRY, STOPPED, RelaySwitch.OFF)
        RelayFailure.LIBRARY -> RelayPage(STOPPED, RelayFailure.LIBRARY.text, HELPER_NEXT, STOPPED, RelaySwitch.OFF)
        RelayFailure.STOP_TIMEOUT -> RelayPage(STOP_SLOW, RelayFailure.STOP_TIMEOUT.text, rowSummary = ROW_STOPPING, switch = RelaySwitch.ON)
        RelayFailure.START_FAILED, null -> RelayPage(STOPPED, RelayFailure.START_FAILED.text, RETRY, STOPPED, RelaySwitch.OFF)
    }

    private fun running(facts: RelayFacts, state: RelayState): RelayPage {
        val inUse = state.allocations > 0
        val useTitle = when {
            inUse -> "In use now"
            facts.lastUsedAt != null -> "Last used ${atTime(facts.lastUsedAt, facts.nowMs, facts.zone)}"
            else -> "Not used yet"
        }
        val useSummary = "Relayed today: ${bytes(state.todayBytes)} of ${limit(state.dailyLimit)}"
        val use = "$useTitle · $useSummary"
        if (state.controlConnected == false) {
            return RelayPage(RUNNING_NO_CONTROL, RUNNING_NO_CONTROL_2, use, "On · waiting for Nostr relays", RelaySwitch.ON,
                running = true, useTitle = useTitle, useSummary = useSummary)
        }
        return RelayPage(RUNNING, null, use, if (inUse) "On · in use now" else ROW_ON, RelaySwitch.ON,
            running = true, useTitle = useTitle, useSummary = useSummary)
    }
}
