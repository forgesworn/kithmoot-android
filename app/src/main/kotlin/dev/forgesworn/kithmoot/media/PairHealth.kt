package dev.forgesworn.kithmoot.media

import kotlin.math.min
import kotlin.random.Random

/**
 * Per-pair self-healing: section 3.4 of the call reliability spec, as a state
 * machine that samples numbers and returns decisions.
 *
 * The question it answers is the one a call actually fails on. A connection can
 * be `connected`, the signalling can be quiet, every object can look healthy,
 * and one direction can still be carrying nothing at all - which is exactly
 * what the reproductions showed. The only honest evidence is whether packets
 * are moving, so that is the only thing this reads.
 *
 * Amendment A3 decides where the feedback comes from: `remote-inbound-rtp`
 * first, because it already tells a sender whether the far end is receiving its
 * RTP, costs no new metadata, and works against far ends that have never heard
 * of any of this. A pairwise `health` signal is added only to report a **single
 * dead slot on an otherwise healthy transport**, which RTCP cannot express and
 * which a roster field would broadcast to the whole room twenty seconds late.
 *
 * Deliberately pure: it owns no connection, samples nothing itself and starts
 * no timers. It is handed counters and a clock and returns what to do, so a
 * ladder that would take a minute of a bad network to walk takes a millisecond
 * here.
 *
 * **Where Android cannot follow the spec.** Section 3.4's ladder has a step
 * that rebuilds on the next route tier, direct to turn. This client has no
 * tiers: one connection is built with STUN and TURN together in a single ICE
 * server list (`CallIceServers`) and nothing ever narrows it, so there is
 * nowhere for that step to go. The ladder here is restart, rebuild, rest, on
 * the `direct` thresholds, and the tier step is the one rung it does not have.
 */

/** Which direction of one media section a counter describes. */
enum class RtpFlow {
    /** `inbound-rtp`: what this device is receiving on that slot. */
    INBOUND,

    /** `remote-inbound-rtp`: what the far end says it is receiving from us. */
    REMOTE_INBOUND,
}

/**
 * One counter, for one direction of one media section.
 *
 * [counter] is whatever that direction's progress is measured by, chosen by
 * whoever read the report: `packetsReceived` for inbound audio,
 * `framesDecoded` for inbound video, and for `remote-inbound-rtp` the first of
 * `roundTripTimeMeasurements`, the report timestamp or packet-loss movement
 * that the platform actually provides. The ladder never asks what it means,
 * only whether it went up.
 */
data class RtpProgress(val mid: String, val flow: RtpFlow, val counter: Long)

/** What one sample decided. */
sealed interface HealthAction {
    /**
     * One slot is dead while the rest of the transport is fine. Tell the far
     * end, and do nothing else: rebuilding a working connection because one
     * camera stopped would cost every other slot on it.
     */
    data class ReportDead(val roles: Set<String>) : HealthAction

    /**
     * The far end says a slot of ours is dead, and our transport is fine.
     * Take the track out and put it back.
     */
    data class RefreshSlot(val role: String) : HealthAction

    /** Ladder step 1: gather again on the connection that exists. */
    data object RestartIce : HealthAction

    /**
     * Ladder step 2, and every step after: throw the connection away and open
     * a new generation.
     *
     * Which generation is the caller's to decide, because the caller is the one
     * that knows what the far end has been claiming - a rebuild goes above both
     * sides' highest, so a number is never reused whatever order the two sides
     * rebuilt in.
     */
    data object Rebuild : HealthAction
}

/** The thresholds of section 3.4. Defaults are the spec's; tests shorten them. */
data class HealthTiming(
    /** How long a slot may be advertised live with no inbound progress. */
    val deadSlotMs: Long = 6_000,
    /** The same again, after the advert turns live or the connection connects,
     *  before anything is judged at all. */
    val graceMs: Long = 6_000,
    /** How long a disconnected transport is given before the ladder starts. */
    val restartAfterMs: Long = 6_000,
    /** How long an ICE restart is given to bring inbound back. */
    val rebuildAfterMs: Long = 8_000,
    /** How long a rebuild is given, before the rest ladder takes over. */
    val nextStepAfterMs: Long = 12_000,
    /**
     * The sender's own threshold, deliberately the receiver's doubled.
     *
     * The side that is missing media acts first; the sending side acts only if
     * the far end plainly did not, which is the backstop for far ends that
     * cannot self-heal - profile 1, and every Android build before this one.
     */
    val rtcpBackstopMs: Long = 14_000,
    /** At most one refresh per slot per this long, however often the far end
     *  complains. */
    val refreshIntervalMs: Long = 10_000,
    /** The rest ladder once the rungs are exhausted, and its cap. */
    val restMs: List<Long> = listOf(5_000, 10_000, 20_000, 40_000),
    val restCapMs: Long = 60_000,
    val restJitter: Double = 0.25,
    /** No more rebuilds than this in [rebuildWindowMs], before the rest ladder
     *  takes over. Rebuild storms on a bad network are the risk section 9
     *  names. */
    val maxRebuilds: Int = 3,
    val rebuildWindowMs: Long = 60_000,
)

/** Where on the ladder a pair currently is. */
enum class LadderStep {
    /** Media is moving, or nothing has been silent long enough to say. */
    HEALTHY,

    /** An ICE restart has gone out on the connection that exists. */
    RESTARTED,

    /** The connection has been thrown away and a new generation opened. */
    REBUILT,

    /** Too many rebuilds too quickly, so this pair waits before trying again. */
    RESTING,
}

/**
 * One pair's health.
 *
 * Fed one [sample] every two seconds, and told when a `health` signal arrives,
 * a connection state changes, or a generation is adopted.
 */
class PairHealth(
    private val timing: HealthTiming = HealthTiming(),
    private val random: () -> Double = { Random.nextDouble() },
) {

    private data class Counter(var value: Long, var movedAt: Long)

    /** The last counter seen for each direction of each media section. */
    private val counters = mutableMapOf<kotlin.Pair<String, RtpFlow>, Counter>()

    /** Slots already reported dead, so the far end is told once rather than
     *  every two seconds. Cleared per slot the moment it carries again. */
    private val reported = mutableSetOf<String>()

    private val refreshedAt = mutableMapOf<String, Long>()

    /** When each rebuild happened, inside the window the cap is counted over. */
    private val rebuiltAt = mutableListOf<Long>()

    var step: LadderStep = LadderStep.HEALTHY
        private set

    /** When the current rung started. Null while nothing is wrong. */
    private var rungSince: Long? = null

    /** When the transport first said it was not connected. */
    private var disconnectedSince: Long? = null

    /**
     * When the clocks were last started.
     *
     * A counter that has never moved is measured from here, which is what makes
     * the threshold "six seconds of silence" rather than "six seconds since the
     * call began". Reset by a connect, a rebuild, a generation adopted.
     */
    private var primedAt: Long = 0

    private var restAttempt: Int = 0
    private var restUntil: Long? = null

    /** Roles this side currently believes it is receiving nothing on. */
    val deadSlots: Set<String> get() = reported.toSet()

    /**
     * A generation was adopted, so start again.
     *
     * Cancels every rung and every rest: an incoming higher generation always
     * wins, which is what stops two sides chasing each other down the ladder.
     */
    fun adopt(now: Long) = reset(now)

    /** Start the clocks again, and stand at the bottom of the ladder. */
    fun reset(now: Long) {
        counters.clear()
        reported.clear()
        step = LadderStep.HEALTHY
        rungSince = null
        disconnectedSince = null
        restUntil = null
        restAttempt = 0
        primedAt = now
    }

    /** The transport's own verdict, as `onConnectionChange` reports it. */
    fun onConnectionState(state: String, now: Long) {
        when (state) {
            "connected" -> {
                disconnectedSince = null
                // Connecting is as good a reason to start the grace as an
                // advert turning live: nothing was ever going to arrive before
                // this moment.
                primedAt = now
                counters.clear()
                reported.clear()
                if (step == LadderStep.RESTARTED || step == LadderStep.REBUILT) {
                    step = LadderStep.HEALTHY
                    rungSince = null
                }
            }
            "disconnected", "failed" -> if (disconnectedSince == null) disconnectedSince = now
            else -> Unit
        }
    }

    /**
     * The far end says these slots of ours are dead.
     *
     * Acted on only while our own transport is fine - if it is not, the ladder
     * is already dealing with something bigger - and at most once per slot per
     * ten seconds, because a far end that is wrong about it will keep saying so.
     */
    fun onHealth(rx: Map<String, String>, now: Long): List<HealthAction> {
        if (step != LadderStep.HEALTHY) return emptyList()
        val actions = mutableListOf<HealthAction>()
        for ((role, verdict) in rx) {
            if (verdict != "dead" || role !in SLOT_KINDS) continue
            val last = refreshedAt[role]
            if (last != null && now - last < timing.refreshIntervalMs) continue
            refreshedAt[role] = now
            actions += HealthAction.RefreshSlot(role)
        }
        return actions
    }

    /**
     * One sample, two seconds after the last.
     *
     * @param now the clock, injected so a ladder that takes a minute of bad
     *   network to walk takes a millisecond here.
     * @param slots the generation's mid-to-role map.
     * @param live the roles the far end says are on. A slot nobody is sending
     *   on is not a slot that has failed.
     * @param progress every counter this sample read.
     */
    fun sample(
        now: Long,
        slots: Map<String, String>,
        live: Set<String>,
        progress: List<RtpProgress>,
    ): List<HealthAction> {
        for (entry in progress) {
            val key = entry.mid to entry.flow
            val known = counters[key]
            if (known == null) counters[key] = Counter(entry.counter, now)
            else if (entry.counter > known.value) {
                known.value = entry.counter
                known.movedAt = now
            }
        }

        restUntil?.let { due ->
            if (now < due) return emptyList()
            restUntil = null
            return listOf(rebuild(now))
        }

        // Nothing is judged inside the grace: before it, silence is only
        // silence, not evidence.
        if (now - primedAt < timing.graceMs) return emptyList()

        val watched = slots.entries.filter { it.value in live }.map { it.key to it.value }
        val dead = watched.filter { (mid, _) -> stalled(mid, RtpFlow.INBOUND, now) >= timing.deadSlotMs }
            .map { it.second }
            .toSet()

        // A slot that came back needs no further apology, and must be able to
        // be reported again if it dies a second time.
        reported.retainAll(dead)

        val transportDown = disconnectedSince?.let { now - it >= timing.restartAfterMs } == true
        val everythingDead = watched.isNotEmpty() && dead.size == watched.size

        if (transportDown || everythingDead) return climb(now)

        if (dead.isNotEmpty()) {
            // One dead slot on a transport that is plainly fine. Telling the far
            // end is the whole repair: it puts the track back, and every other
            // slot on this connection carries on undisturbed.
            val fresh = dead - reported
            if (fresh.isEmpty()) return emptyList()
            reported += fresh
            return listOf(HealthAction.ReportDead(fresh))
        }

        if (step == LadderStep.HEALTHY) rungSince = null
        return backstop(now)
    }

    /** How long a direction of a media section has been silent. */
    private fun stalled(mid: String, flow: RtpFlow, now: Long): Long =
        now - (counters[mid to flow]?.movedAt ?: primedAt)

    /**
     * Our outbound is not reaching the far end, and the far end has not said so
     * itself.
     *
     * The backstop for a far end that cannot self-heal - a profile-1 peer, or
     * any Android build before this one - at twice the receiver's own threshold
     * so that the side missing media always acts first. With the generation
     * rule a double rebuild is harmless anyway.
     */
    private fun backstop(now: Long): List<HealthAction> {
        if (step != LadderStep.HEALTHY) return emptyList()
        val outbound = counters.keys.filter { it.second == RtpFlow.REMOTE_INBOUND }
        // Nothing is inferred from a report that does not carry the numbers:
        // an absent `remote-inbound-rtp` is silence about the far end, not
        // evidence against it.
        if (outbound.isEmpty()) return emptyList()
        if (outbound.any { stalled(it.first, RtpFlow.REMOTE_INBOUND, now) < timing.rtcpBackstopMs }) return emptyList()
        return climb(now)
    }

    /** The next rung, and the rung after that. */
    private fun climb(now: Long): List<HealthAction> {
        val since = rungSince
        return when (step) {
            LadderStep.HEALTHY -> {
                step = LadderStep.RESTARTED
                rungSince = now
                // Inside the generation: the m-lines, their order and their
                // mids do not move, so the far end applies it to the session it
                // already has.
                listOf(HealthAction.RestartIce)
            }
            LadderStep.RESTARTED ->
                if (since == null || now - since < timing.rebuildAfterMs) emptyList()
                else listOf(rebuild(now))
            LadderStep.REBUILT ->
                if (since == null || now - since < timing.nextStepAfterMs) emptyList()
                else listOf(rebuild(now))
            LadderStep.RESTING -> emptyList()
        }
    }

    /**
     * A new connection at a new generation, unless this pair has rebuilt too
     * often lately - in which case it rests first.
     *
     * There is no terminal state. While both devices are in the roster the
     * ladder starts again from the top, because a pair that has given up is a
     * pair nothing will ever repair; the interface says "reconnecting" and
     * means it.
     */
    private fun rebuild(now: Long): HealthAction {
        rebuiltAt.removeAll { now - it > timing.rebuildWindowMs }
        if (rebuiltAt.size >= timing.maxRebuilds) {
            step = LadderStep.RESTING
            val base = timing.restMs.getOrElse(restAttempt) { timing.restCapMs }
            val spread = 1 + (random() * 2 - 1) * timing.restJitter
            restAttempt += 1
            restUntil = now + (min(base, timing.restCapMs) * spread).toLong()
            rebuiltAt.clear()
        } else {
            step = LadderStep.REBUILT
            restAttempt = 0
        }
        rebuiltAt += now
        rungSince = now
        primedAt = now
        counters.clear()
        reported.clear()
        disconnectedSince = null
        return HealthAction.Rebuild
    }
}
