package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.session.Roles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The per-pair rebuild ladder, walked in milliseconds.
 *
 * Every threshold in section 3.4 is expressed in seconds of a bad network, so
 * none of these cases can be produced on purpose with two handsets. They are
 * produced here by handing the machine counters that do not move.
 */
class PairHealthTest {

    private val slots = mapOf(
        "0" to Roles.MIC,
        "1" to Roles.CAMERA,
        "2" to Roles.SCREEN,
        "3" to Roles.SCREEN_AUDIO,
    )

    /** No jitter, so a rest can be asserted to the millisecond. */
    private val noJitter: () -> Double = { 0.5 }

    private fun health(timing: HealthTiming = HealthTiming()) = PairHealth(timing, noJitter)

    private fun inbound(vararg counters: kotlin.Pair<String, Long>) =
        counters.map { RtpProgress(it.first, RtpFlow.INBOUND, it.second) }

    private fun outbound(vararg counters: kotlin.Pair<String, Long>) =
        counters.map { RtpProgress(it.first, RtpFlow.REMOTE_INBOUND, it.second) }

    /** Feed one pair samples every two seconds up to [until], and collect what
     *  it decided. */
    private fun run(
        pair: PairHealth,
        from: Long,
        until: Long,
        live: Set<String>,
        progress: (Long) -> List<RtpProgress>,
    ): List<kotlin.Pair<Long, HealthAction>> {
        val log = mutableListOf<kotlin.Pair<Long, HealthAction>>()
        var now = from
        while (now <= until) {
            for (action in pair.sample(now, slots, live, progress(now))) log += now to action
            now += 2_000
        }
        return log
    }

    @Test
    fun `nothing is judged inside the grace`() {
        // Before it, silence is only silence: nothing was ever going to arrive
        // in the first moments of a connection.
        val pair = health()
        pair.reset(0)

        val log = run(pair, 0, HealthTiming().graceMs - 1, setOf(Roles.CAMERA)) { emptyList() }

        assertTrue(log.isEmpty())
        assertEquals(LadderStep.HEALTHY, pair.step)
    }

    @Test
    fun `one dead slot on a healthy transport is reported and nothing else`() {
        // Rebuilding a working connection because one camera stopped would cost
        // every other slot on it. The far end puts the track back instead.
        val pair = health()
        pair.reset(0)
        var frames = 0L

        val log = run(pair, 0, 20_000, setOf(Roles.MIC, Roles.CAMERA)) {
            // The microphone is moving; the camera is not.
            inbound("0" to ++frames)
        }

        assertEquals(
            listOf(HealthAction.ReportDead(setOf(Roles.CAMERA))),
            log.map { it.second },
            "told once, not every two seconds, and no rung is climbed",
        )
        assertEquals(LadderStep.HEALTHY, pair.step)
    }

    @Test
    fun `a slot that comes back can be reported again if it dies twice`() {
        val pair = health()
        pair.reset(0)
        var mic = 0L
        var camera = 0L

        // Camera dead.
        run(pair, 0, 8_000, setOf(Roles.MIC, Roles.CAMERA)) { inbound("0" to ++mic) }
        assertEquals(setOf(Roles.CAMERA), pair.deadSlots)

        // Camera back.
        val alive = run(pair, 10_000, 14_000, setOf(Roles.MIC, Roles.CAMERA)) {
            inbound("0" to ++mic, "1" to ++camera)
        }
        assertTrue(alive.isEmpty())
        assertEquals(emptySet(), pair.deadSlots)

        // Camera dead again.
        val again = run(pair, 16_000, 24_000, setOf(Roles.MIC, Roles.CAMERA)) { inbound("0" to ++mic) }
        assertEquals(listOf(HealthAction.ReportDead(setOf(Roles.CAMERA))), again.map { it.second })
    }

    @Test
    fun `a slot nobody is sending on cannot be dead`() {
        val pair = health()
        pair.reset(0)
        var mic = 0L

        val log = run(pair, 0, 30_000, setOf(Roles.MIC)) { inbound("0" to ++mic) }

        assertTrue(log.isEmpty(), "three silent slots and one live one is a healthy pair")
    }

    @Test
    fun `every live slot dead walks restart, then rebuild`() {
        val timing = HealthTiming()
        val pair = health(timing)
        pair.reset(0)

        val log = run(pair, 0, 40_000, setOf(Roles.MIC, Roles.CAMERA)) { emptyList() }

        val first = log.first()
        assertEquals(HealthAction.RestartIce, first.second)
        assertEquals(timing.graceMs, first.first, "the first rung is taken the moment the grace is out")

        val rebuild = log.drop(1).first()
        assertEquals(HealthAction.Rebuild, rebuild.second)
        assertEquals(
            timing.rebuildAfterMs,
            rebuild.first - first.first,
            "and a restart gets eight seconds to bring media back before the connection goes",
        )
        assertEquals(LadderStep.REBUILT, pair.step)
    }

    @Test
    fun `a restart that works takes the pair back off the ladder`() {
        val pair = health()
        pair.reset(0)
        var frames = 0L

        val restart = run(pair, 0, 6_000, setOf(Roles.CAMERA)) { emptyList() }
        assertEquals(listOf(HealthAction.RestartIce), restart.map { it.second })

        pair.onConnectionState("connected", 7_000)
        val after = run(pair, 8_000, 40_000, setOf(Roles.CAMERA)) { inbound("1" to ++frames) }

        assertTrue(after.isEmpty())
        assertEquals(LadderStep.HEALTHY, pair.step)
    }

    @Test
    fun `a transport that says disconnected starts the ladder without waiting for the counters`() {
        val pair = health()
        pair.reset(0)
        var frames = 0L
        // Media was flowing right up to the moment the transport gave up, so
        // the dead-slot threshold would not have fired for another six seconds.
        pair.sample(0, slots, setOf(Roles.CAMERA), inbound("1" to ++frames))
        pair.onConnectionState("disconnected", 2_000)

        // Frames keep arriving throughout, so no slot is dead and the transport's
        // own verdict is the only thing the ladder has to go on.
        val log = run(pair, 4_000, 10_000, setOf(Roles.CAMERA)) { inbound("1" to ++frames) }

        assertEquals(HealthAction.RestartIce, log.first().second)
        assertEquals(8_000, log.first().first, "six seconds after it said so")
    }

    @Test
    fun `the RTCP backstop fires at fourteen seconds when the far end says nothing`() {
        // The side missing media acts first; the sending side acts only if the
        // far end plainly did not, which is the backstop for a far end that
        // cannot self-heal at all.
        val timing = HealthTiming()
        val pair = health(timing)
        pair.reset(0)
        var frames = 0L

        val log = run(pair, 0, 30_000, setOf(Roles.CAMERA)) {
            // Our inbound is fine. Our outbound is not reaching them.
            inbound("1" to ++frames) + outbound("1" to 7)
        }

        assertEquals(HealthAction.RestartIce, log.first().second)
        assertEquals(timing.rtcpBackstopMs, log.first().first)
    }

    @Test
    fun `a moving RTCP counter is never a reason to do anything`() {
        val pair = health()
        pair.reset(0)
        var frames = 0L
        var rtt = 0L

        val log = run(pair, 0, 60_000, setOf(Roles.CAMERA)) {
            inbound("1" to ++frames) + outbound("1" to ++rtt)
        }

        assertTrue(log.isEmpty())
    }

    @Test
    fun `an absent RTCP report is silence about the far end, not evidence against it`() {
        val pair = health()
        pair.reset(0)
        var frames = 0L

        val log = run(pair, 0, 60_000, setOf(Roles.CAMERA)) { inbound("1" to ++frames) }

        assertTrue(log.isEmpty(), "nothing is inferred from a report that does not carry the numbers")
    }

    @Test
    fun `three rebuilds in a minute and the pair rests, with jitter, and then tries again`() {
        // Rebuild storms on a bad network are the risk section 9 names.
        val timing = HealthTiming()
        val pair = health(timing)
        pair.reset(0)

        val log = run(pair, 0, 300_000, setOf(Roles.CAMERA)) { emptyList() }
        val rebuilds = log.filter { it.second == HealthAction.Rebuild }

        assertTrue(rebuilds.size >= 4, "a pair in the roster never reaches a terminal state")
        assertTrue(
            log.any { it.second == HealthAction.RestartIce },
            "and the ladder starts at the top, with the cheap repair",
        )
        // The gap that is longer than any rung is the rest.
        val gaps = rebuilds.zipWithNext { a, b -> b.first - a.first }
        assertTrue(
            gaps.any { it >= timing.restMs.first() },
            "the fourth rebuild in a minute waits instead: $gaps",
        )
    }

    @Test
    fun `adopting a generation cancels every rung and every rest`() {
        // An incoming higher generation always wins, which is what stops two
        // sides chasing each other down the ladder.
        val pair = health()
        pair.reset(0)
        run(pair, 0, 20_000, setOf(Roles.CAMERA)) { emptyList() }
        assertTrue(pair.step != LadderStep.HEALTHY)

        pair.adopt(21_000)

        assertEquals(LadderStep.HEALTHY, pair.step)
        assertTrue(
            run(pair, 22_000, 25_000, setOf(Roles.CAMERA)) { emptyList() }.isEmpty(),
            "and the grace starts again from the adoption",
        )
    }

    @Test
    fun `a health signal refreshes the slot it names, at most once every ten seconds`() {
        val timing = HealthTiming()
        val pair = health(timing)
        pair.reset(0)

        assertEquals(
            listOf(HealthAction.RefreshSlot(Roles.CAMERA)),
            pair.onHealth(mapOf(Roles.CAMERA to "dead"), 1_000),
        )
        assertTrue(
            pair.onHealth(mapOf(Roles.CAMERA to "dead"), 5_000).isEmpty(),
            "a far end that is wrong about it will keep saying so",
        )
        assertEquals(
            listOf(HealthAction.RefreshSlot(Roles.CAMERA)),
            pair.onHealth(mapOf(Roles.CAMERA to "dead"), 1_000 + timing.refreshIntervalMs),
        )
    }

    @Test
    fun `a health signal saying a slot is fine, or naming a slot that does not exist, does nothing`() {
        val pair = health()
        pair.reset(0)

        assertTrue(pair.onHealth(mapOf(Roles.CAMERA to "ok"), 1_000).isEmpty())
        assertTrue(pair.onHealth(mapOf("hologram" to "dead"), 1_000).isEmpty())
    }

    @Test
    fun `a health signal is ignored while our own transport is already in trouble`() {
        // The ladder is dealing with something bigger, and putting a track back
        // into a slot on a connection that is about to be thrown away helps
        // nobody.
        val pair = health()
        pair.reset(0)
        run(pair, 0, 8_000, setOf(Roles.CAMERA)) { emptyList() }
        assertEquals(LadderStep.RESTARTED, pair.step)

        assertTrue(pair.onHealth(mapOf(Roles.CAMERA to "dead"), 9_000).isEmpty())
    }
}
