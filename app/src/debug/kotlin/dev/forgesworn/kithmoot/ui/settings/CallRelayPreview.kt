package dev.forgesworn.kithmoot.ui.settings

import dev.forgesworn.kithmoot.callrelay.CANNOT_HOST_TEXT
import dev.forgesworn.kithmoot.callrelay.RelayFailure
import dev.forgesworn.kithmoot.callrelay.RelayGate
import dev.forgesworn.kithmoot.callrelay.RelayMapping
import dev.forgesworn.kithmoot.callrelay.RelayPhase
import dev.forgesworn.kithmoot.callrelay.RelayRefusal
import dev.forgesworn.kithmoot.callrelay.RelayState

/**
 * The Developer page's "Call relay screen preview" (settings spec, 11.5):
 * every state of the call relay page, pinned by hand, so the owner can see
 * each without a hot phone or a carrier-grade line. Debug builds only: the
 * list is empty in any other build, and nothing reaches it from a release.
 */
internal object CallRelayPreviews {
    /** One screen to preview: its id (the spec's state id), and the facts the page is then a function of. */
    class Entry(val id: String, val label: String, val facts: (nowMs: Long) -> RelayFacts)

    private val mapping = RelayMapping("upnp", 10, 10, permanent = false, restored = 0)
    private fun running(allocations: Long = 0, control: Boolean? = true) = RelayState(phase = RelayPhase.RUNNING, wanted = true, allocations = allocations,
        bytesRelayed = 1_200_000_000, todayBytes = if (allocations > 0) 1_200_000_000 else 0, controlConnected = control, mapping = mapping, bind = "192.168.1.20", listenPort = 23456)

    private fun entry(id: String, label: String, state: RelayState? = null, refusal: RelayRefusal? = null, handPaired: Boolean = false, lastUsedMinutesAgo: Long? = null) =
        Entry(id, label) { now -> RelayFacts(state, refusal, handPaired, lastUsedMinutesAgo?.let { now - it * 60_000 }, now) }

    /** The ids the owner can pick, in the spec's order; empty unless [debug]. */
    fun entries(debug: Boolean): List<Entry> = if (!debug) emptyList() else buildList {
        add(entry("S1", "Off"))
        add(entry("S3", "Starting", RelayState(phase = RelayPhase.STARTING, wanted = true)))
        add(entry("S4", "Running, idle", running()))
        add(entry("S4L", "Running, last used 20 minutes ago", running(), lastUsedMinutesAgo = 20))
        add(entry("S5", "Running, in use", running(allocations = 3)))
        add(entry("S14", "Running, waiting for Nostr relays", running(control = false)))
        add(entry("S11", "Waiting for a network", RelayState(phase = RelayPhase.OFF, wanted = true)))
        add(entry("S12", "Stopping", RelayState(phase = RelayPhase.STOPPING, wanted = true)))
        for (code in CANNOT_HOST_TEXT.keys) add(entry("S7:$code", "Can't run here: $code", RelayState(phase = RelayPhase.CANNOT_HOST, wanted = true, reason = code)))
        add(entry("S7x", "Can't run, gave up", RelayState(phase = RelayPhase.CANNOT_HOST, reason = "no-answer")))
        add(entry("S8a", "Restarting after a crash", RelayState(phase = RelayPhase.FAILED, wanted = true, failure = RelayFailure.CRASHED)))
        add(entry("S8b", "Stopped after two crashes", RelayState(phase = RelayPhase.FAILED, failure = RelayFailure.CRASHED_TWICE)))
        add(entry("S8c", "Couldn't start", RelayState(phase = RelayPhase.FAILED, failure = RelayFailure.START_FAILED)))
        add(entry("S8c2", "Didn't get ready in time", RelayState(phase = RelayPhase.FAILED, failure = RelayFailure.START_TIMEOUT)))
        add(entry("S8d", "Missing from this install", RelayState(phase = RelayPhase.FAILED, failure = RelayFailure.LIBRARY)))
        add(entry("S8e", "Hasn't stopped", RelayState(phase = RelayPhase.FAILED, failure = RelayFailure.STOP_TIMEOUT)))
        add(entry("S8h", "Saved keys unreadable", refusal = RelayRefusal.KEYS_UNREADABLE))
        add(entry("S9", "Daily limit reached", RelayState(phase = RelayPhase.PAUSED, wanted = true, gate = RelayGate.DAILY_LIMIT, todayBytes = 2_000_000_000)))
        add(entry("S10a", "Own Spry in use", handPaired = true))
        add(entry("S10b", "No account yet", refusal = RelayRefusal.NO_ACCOUNT))
        add(entry("S10c", "Another account", refusal = RelayRefusal.OTHER_ACCOUNT))
        add(entry("S10d", "No usable Nostr relay", refusal = RelayRefusal.NO_CONTROL_RELAYS))
        for (gate in RelayGate.entries) if (gate != RelayGate.DAILY_LIMIT) add(entry("AP:${gate.name}", "Paused: ${gate.name}", RelayState(phase = RelayPhase.PAUSED, wanted = true, gate = gate)))
    }

    /** The facts for a pinned id, or null for an id this build does not list. */
    fun facts(id: String, nowMs: Long): RelayFacts? = entries(dev.forgesworn.kithmoot.BuildConfig.DEBUG).firstOrNull { it.id == id }?.facts?.invoke(nowMs)
}
