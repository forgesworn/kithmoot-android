package dev.forgesworn.kithmoot.ui.room

import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.transformLatest

/**
 * What pressing Join means, given what this device actually has to join with.
 *
 * Before this there were two answers and both were wrong in the same way. A
 * press with no engine behind it said "Audio and video are still starting.
 * Try again shortly." and threw the press away; a press while a leave was
 * still settling returned without a word. Neither remembered that somebody
 * had asked to be on the call, so the room that opened at a non-active epoch
 * could be pressed at for as long as the person had patience.
 */
sealed interface JoinDecision {
    /** Already on the call, or a leave has not settled: nothing to do. */
    data object Ignore : JoinDecision

    /** Audio and video exist here. Go on the call. */
    data object Now : JoinDecision

    /** Nothing to join with yet and it is on its way. Remember the press and
     *  carry it out when the engine arrives. */
    data object WhenReady : JoinDecision

    /** Nothing to join with, and nothing coming. Say why. */
    data class Refuse(val message: String) : JoinDecision
}

/**
 * Pure decision behind `RoomViewModel.joinCall`.
 *
 * `mediaReady` is "an engine exists here", `mediaStarting` is "one is being
 * built, or the room is waiting for its epoch before building one", and
 * `mediaFault` is the engine having failed for good.
 */
fun joinDecision(
    onCall: Boolean,
    changing: Boolean,
    mediaReady: Boolean,
    mediaStarting: Boolean,
    mediaFault: String?,
): JoinDecision = when {
    changing || onCall -> JoinDecision.Ignore
    mediaReady -> JoinDecision.Now
    mediaFault != null -> JoinDecision.Refuse(mediaFault)
    mediaStarting -> JoinDecision.WhenReady
    else -> JoinDecision.Refuse("This device has no audio or video in this room.")
}

/** The line beside the call control while a press is waiting on the engine. */
const val JOIN_PENDING_LABEL = "Joining when audio and video are ready…"

/**
 * What to say when a mic, camera or share press finds no engine here.
 *
 * It used to say "No camera on this device." - on a phone with a camera,
 * answering a ring while the engine was still being built, or mid-call after
 * an epoch change took the engine down to rebuild it. The device was never
 * the problem; the call was not ready yet.
 */
/**
 * Said on a call that has no relay once a link to someone fails or stays stuck
 * (the web's `RELAY_MISSING_NOTICE`). It names a call relay and where to add
 * one: "relay" alone reads as the room's Nostr relays, which carry no media.
 */
const val RELAY_MISSING_NOTICE = "No call relay is set up on this device, so this call may not connect on some networks. Set one up in Settings › Calls."

/** Link states that mean "still trying": the transport's and ICE's own words both reach the map. */
private val WAITING_LINK = setOf("new", "checking", "connecting")

/**
 * How long a link may keep trying before the relay line says why. A direct
 * route that works is found in a few seconds; a link with no route can sit
 * on "connecting" indefinitely on Android without ever reporting "failed".
 */
const val STUCK_LINK_AFTER_MS = 20_000L

/**
 * The relay line, or null. Shown while this device is on a call with no relay
 * and a link to someone has failed, dropped, or is one of [stuck] and still
 * trying: the network between them may need a relay, and without one nothing
 * else will get the call through.
 */
fun relayMissingNote(onCall: Boolean, hasRelay: Boolean, connections: Map<String, String>, stuck: Set<String> = emptySet()): String? =
    RELAY_MISSING_NOTICE.takeIf {
        onCall && !hasRelay && (
            connections.values.any { it == "failed" || it == "disconnected" } ||
                stuck.any { connections[it] in WAITING_LINK })
    }

/**
 * The links that have been trying for [afterMs] without getting anywhere.
 *
 * A link's clock starts when it first enters a waiting state and keeps
 * running while it moves between them ("checking" and "connecting" both
 * arrive); it stops when the link connects, fails or goes. A new set is
 * emitted on every change and again when a link crosses [afterMs].
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun stuckLinks(connections: kotlinx.coroutines.flow.Flow<Map<String, String>>, afterMs: Long = STUCK_LINK_AFTER_MS,
    now: () -> Long = android.os.SystemClock::elapsedRealtime): kotlinx.coroutines.flow.Flow<Set<String>> = kotlinx.coroutines.flow.flow {
    val since = HashMap<String, Long>()
    emitAll(connections.transformLatest { states ->
        val at = now()
        since.keys.retainAll { states[it] in WAITING_LINK }
        for ((peer, state) in states) if (state in WAITING_LINK) since.putIfAbsent(peer, at)
        while (true) {
            val t = now()
            emit(since.filterValues { t - it >= afterMs }.keys.toSet())
            val wait = since.values.map { it + afterMs - t }.filter { it > 0 }.minOrNull() ?: break
            kotlinx.coroutines.delay(wait)
        }
    }.distinctUntilChanged())
}

fun mediaMissingNote(mediaStarting: Boolean, joinPending: Boolean, mediaFault: String?): String = when {
    mediaFault != null -> mediaFault
    mediaStarting || joinPending -> "The call is still connecting. Try again in a moment."
    else -> "Audio and video are not running in this room yet."
}

/**
 * Which call this device says it is on when it declares one.
 *
 * The room's current call when one is visible. Failing that, the call this
 * device was last on in this room, if it has not pressed Leave since: an
 * epoch change rebuilds the session mid-call, and the new one cannot see the
 * call until its relays catch up. A fresh id only for a press of Join.
 *
 * Never a fresh id otherwise. A fresh id is a new call, and a new call rings
 * every phone in the room in this person's name: after one call broke up, a
 * phone whose last track emission landed behind its Leave, or whose relays
 * were a beat behind, started a second call nobody had asked for, and the
 * whole room rang with "TheCryptoDonkey is calling".
 */
fun callToDeclare(visible: String?, remembered: String?, pressed: Boolean, fresh: () -> String): String? =
    visible ?: remembered ?: if (pressed) fresh() else null
