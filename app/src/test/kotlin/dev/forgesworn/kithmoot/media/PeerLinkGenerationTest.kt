package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.session.CALL_PROFILE_2
import dev.forgesworn.kithmoot.session.Roles
import dev.forgesworn.kithmoot.support.FakePeerConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Generations, the reliable channel and the health signal, as a profile-2 link
 * actually uses them.
 *
 * `PeerGenerationTest` pins the table and `SignalChannelTest` pins the channel;
 * this is the join between them - that every profile-2 signal carries the pair's
 * generation and this connection's id, that an offer from a connection the far
 * end has replaced never reaches this one, and that the rebuild is asked for
 * rather than assumed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerLinkGenerationTest {

    private val room = "cc".repeat(32)
    private val low = "aa".repeat(32)
    private val high = "ff".repeat(32)
    private val theirConn = "2222222222222222"

    private class Recorder {
        val sent = mutableListOf<SignalEnvelope>()
        suspend fun send(envelope: SignalEnvelope) {
            sent += envelope
        }

        fun types() = sent.map { it.type }
    }

    private class Rebuilds {
        val asked = mutableListOf<kotlin.Pair<Long, Boolean>>()
        suspend fun onRebuild(gen: Long, open: Boolean) {
            asked += gen to open
        }
    }

    private val fourSlots = mapOf(
        "0" to Roles.MIC,
        "1" to Roles.CAMERA,
        "2" to Roles.SCREEN,
        "3" to Roles.SCREEN_AUDIO,
    )

    private fun offeredSdp() = buildString {
        appendLine("v=0")
        listOf("audio", "video", "video", "audio").forEachIndexed { index, kind ->
            appendLine("m=$kind 9 UDP/TLS/RTP/SAVPF 111")
            appendLine("a=mid:$index")
        }
    }

    private fun TestScope.link(
        local: String,
        remote: String,
        connection: FakePeerConnection,
        recorder: Recorder,
        rebuilds: Rebuilds = Rebuilds(),
        downgrades: MutableList<Unit> = mutableListOf(),
        healths: MutableList<Map<String, String>> = mutableListOf(),
    ) = PeerLink(
        local, remote, connection, room, recorder::send,
        callProfile = CALL_PROFILE_2,
        scope = backgroundScope,
        onRebuild = rebuilds::onRebuild,
        onDowngrade = { downgrades += Unit },
        onHealthSignal = { healths += it },
        newConnectionId = { "1111111111111111" },
    )

    private fun offer(gen: Long, seq: Long = 1, conn: String = theirConn, slots: Map<String, String>? = fourSlots) =
        SignalEnvelope(
            toDevice = high,
            type = SignalType.OFFER,
            roomId = room,
            sdp = offeredSdp(),
            gen = gen,
            conn = conn,
            seq = seq,
            slots = slots,
        )

    @Test
    fun `every profile-2 signal carries the generation and this connection's id`() = runTest {
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val link = link(high, low, connection, recorder)

        link.openSlots(emptyList(), gen = 4)
        link.onLocalCandidate(IceCandidateData("candidate:1 1 udp 1 10.0.0.1 1 typ host"))
        runCurrent()

        assertEquals(4, link.generation)
        assertEquals("1111111111111111", link.connectionId)
        assertTrue(recorder.sent.all { it.gen == 4L && it.conn == "1111111111111111" })
        assertEquals(listOf(1L, 2L), recorder.sent.map { it.seq }, "gapless, from one, per connection")
    }

    @Test
    fun `the answering side takes the generation the offer opened`() = runTest {
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val link = link(low, high, connection, recorder)

        link.onRemoteSignal(offer(gen = 7, seq = 3))
        runCurrent()

        assertEquals(7, link.generation)
        assertEquals(7, link.lastRemoteGeneration)
        val answer = recorder.sent.single()
        assertEquals(SignalType.ANSWER, answer.type)
        assertEquals(7L, answer.gen)
        assertEquals(theirConn, answer.peerConn, "addressed at the connection that offered")
        assertEquals(3L, answer.re)
        assertEquals(3L, answer.ack, "and acknowledging it, at no cost of its own")
    }

    @Test
    fun `an offer from an older generation is answered with a sync and nothing else`() = runTest {
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val link = link(high, low, connection, recorder)
        link.openSlots(emptyList(), gen = 5)
        runCurrent()
        recorder.sent.clear()

        link.onRemoteSignal(offer(gen = 3, seq = 1))
        runCurrent()

        val sync = recorder.sent.single()
        assertEquals(SignalType.SYNC, sync.type)
        assertEquals(5L, sync.gen)
        assertEquals(1, connection.remoteDescriptions.size.let { 1 - it }, "the old offer was never applied")
    }

    @Test
    fun `a newer generation is not adopted here but asked for`() = runTest {
        // Adopting means closing a connection and building another, and only
        // the caller can: it owns the factory. The offer is deliberately not
        // acknowledged, so the far end keeps asking and the new connection is
        // the one that answers.
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val rebuilds = Rebuilds()
        val link = link(high, low, connection, recorder, rebuilds)
        link.openSlots(emptyList(), gen = 2)
        runCurrent()
        recorder.sent.clear()

        link.onRemoteSignal(offer(gen = 9, seq = 1))
        runCurrent()

        assertEquals(listOf(9L to false), rebuilds.asked)
        assertTrue(recorder.sent.none { it.type == SignalType.ANSWER || it.type == SignalType.ACK })
    }

    @Test
    fun `the polite side gives up its connection when both opened a generation`() = runTest {
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val rebuilds = Rebuilds()
        // low is polite against high.
        val link = link(low, high, connection, recorder, rebuilds)
        link.openSlots(emptyList(), gen = 3)
        runCurrent()

        link.onRemoteSignal(offer(gen = 3, seq = 1))
        runCurrent()

        assertEquals(listOf(3L to false), rebuilds.asked)
    }

    @Test
    fun `the impolite side keeps its own offer and asks for nothing`() = runTest {
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val rebuilds = Rebuilds()
        val link = link(high, low, connection, recorder, rebuilds)
        link.openSlots(emptyList(), gen = 3)
        runCurrent()

        link.onRemoteSignal(offer(gen = 3, seq = 1))
        runCurrent()

        assertTrue(rebuilds.asked.isEmpty())
        assertEquals(1, link.offersIgnored)
        assertEquals(SignalingState.HAVE_LOCAL_OFFER, connection.signalingState())
    }

    @Test
    fun `an offer at our generation from a connection we never heard of goes up a generation`() = runTest {
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val rebuilds = Rebuilds()
        val link = link(low, high, connection, recorder, rebuilds)
        link.onRemoteSignal(offer(gen = 2, seq = 1))
        runCurrent()

        // Now a different connection, at the same generation, with nothing of
        // ours outstanding to explain it. A protocol error; going up is the
        // only repair that cannot be argued with.
        link.onRemoteSignal(offer(gen = 2, seq = 1, conn = "3333333333333333"))
        runCurrent()

        assertEquals(listOf(3L to true), rebuilds.asked)
    }

    @Test
    fun `a profile-1 shaped signal from a device believed profile 2 downgrades the pair`() = runTest {
        // What a far end reloading into an older build looks like. Nothing here
        // can serve it: it has no generation to belong to and no connection id
        // to be addressed at.
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val downgrades = mutableListOf<Unit>()
        val link = link(low, high, connection, recorder, downgrades = downgrades)

        link.onRemoteSignal(SignalType.OFFER, sdp = "old-offer")
        runCurrent()

        assertEquals(1, downgrades.size)
        assertNull(link.slots)
        assertTrue(recorder.sent.isEmpty())
    }

    @Test
    fun `an unacknowledged answer is asked about again`() = runTest {
        // H1's exact failure, at the level of a real link: profile 1 sent an
        // answer once and never again, so one lost answer left the pair blind.
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val link = link(low, high, connection, recorder)

        link.onRemoteSignal(offer(gen = 1, seq = 1))
        runCurrent()
        assertEquals(1, recorder.sent.size)

        // Past the longest a jittered one second step may be.
        advanceTimeBy(1_300)
        runCurrent()

        assertEquals(
            listOf(SignalType.ANSWER, SignalType.ANSWER),
            recorder.types(),
            "and it costs no acknowledgement of its own: the answer carried that",
        )
        assertEquals(1L, recorder.sent.last().seq, "the same answer, under its original seq")
    }

    @Test
    fun `an ICE restart offers again inside the generation`() = runTest {
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val link = link(high, low, connection, recorder)
        link.openSlots(emptyList(), gen = 2)
        runCurrent()
        // The far end answers, so the connection is back in `stable`.
        link.onRemoteSignal(
            SignalEnvelope(high, SignalType.ANSWER, room, sdp = "their-answer", gen = 2, conn = theirConn, seq = 1, re = 1),
        )
        runCurrent()
        recorder.sent.clear()

        link.restartIce()
        runCurrent()

        val restart = recorder.sent.single()
        assertEquals(SignalType.OFFER, restart.type)
        assertEquals(true, restart.restart)
        assertEquals(2L, restart.gen, "inside the generation: the m-lines and their mids do not move")
        assertNull(restart.slots, "and it opens nothing, so it carries no slot map")
        assertEquals(4, connection.slotTransceivers.size)
    }

    @Test
    fun `a health signal is reported unreliably and delivered to the caller`() = runTest {
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val healths = mutableListOf<Map<String, String>>()
        val link = link(low, high, connection, recorder, healths = healths)
        link.onRemoteSignal(offer(gen = 1, seq = 1))
        runCurrent()
        recorder.sent.clear()

        link.reportHealth(mapOf(Roles.CAMERA to "dead"))
        runCurrent()
        val health = recorder.sent.single()
        assertEquals(SignalType.HEALTH, health.type)
        assertEquals(mapOf(Roles.CAMERA to "dead"), health.rx)
        assertNull(health.seq, "what is being received right now is its whole value; a stale copy is worse than none")

        link.onRemoteSignal(
            SignalEnvelope(high, SignalType.HEALTH, room, gen = 1, conn = theirConn, rx = mapOf(Roles.MIC to "dead")),
        )
        runCurrent()
        assertEquals(listOf(mapOf(Roles.MIC to "dead")), healths)
    }

    @Test
    fun `refreshing a slot takes the track out and puts it back`() = runTest {
        // The repair for the one case RTCP cannot express: one slot the far end
        // says it is receiving nothing on, while the transport is plainly fine.
        val connection = FakePeerConnection()
        val link = link(high, low, connection, Recorder())
        link.openSlots(listOf(SlotTrack(Roles.CAMERA, "camera-track")), gen = 1)
        runCurrent()
        val before = connection.trackSwaps.size

        link.refreshSlot(Roles.CAMERA)

        assertEquals(
            listOf<Any?>(null, "camera-track"),
            connection.trackSwaps.drop(before).map { it.second },
        )
    }

    @Test
    fun `closing stops the retransmission for good`() = runTest {
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val link = link(high, low, connection, recorder)
        link.openSlots(emptyList(), gen = 1)
        runCurrent()

        link.close()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, recorder.sent.size)
        assertEquals(0, link.queueDepth)
        assertTrue(connection.closed)
    }
}
