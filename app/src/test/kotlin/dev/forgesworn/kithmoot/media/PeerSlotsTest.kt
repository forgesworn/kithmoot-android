package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.session.CALL_PROFILE_2
import dev.forgesworn.kithmoot.session.Roles
import dev.forgesworn.kithmoot.support.FakePeerConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixed media slots, against a fake connection that describes what it was
 * asked to create.
 *
 * H1 is that a camera turned off and on again costs an m-line, an offer and an
 * answer, and one lost answer in that exchange leaves a pair blind for the rest
 * of the call. Every case here is the same measurement of the same claim: a
 * media change on a profile-2 pair produces no offer, no answer and no new
 * m-line, so there is nothing for a relay to lose.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerSlotsTest {

    private val room = "cc".repeat(32)
    private val low = "aa".repeat(32)
    private val high = "ff".repeat(32)

    private class Recorder {
        val sent = mutableListOf<SignalEnvelope>()
        suspend fun send(envelope: SignalEnvelope) {
            sent += envelope
        }

        fun types() = sent.map { it.type }
    }

    /** The impolite side, which is the one that opens a generation. */
    private fun TestScope.opener(connection: FakePeerConnection, recorder: Recorder) = PeerLink(
        high, low, connection, room, recorder::send,
        callProfile = CALL_PROFILE_2,
        scope = backgroundScope,
        newConnectionId = { "1111111111111111" },
    )

    /** The polite side, which creates nothing of its own and answers. */
    private fun TestScope.answerer(connection: FakePeerConnection, recorder: Recorder) = PeerLink(
        low, high, connection, room, recorder::send,
        callProfile = CALL_PROFILE_2,
        scope = backgroundScope,
        newConnectionId = { "2222222222222222" },
    )

    private fun track(role: String) = SlotTrack(role, "track-$role")

    /** The four mids the fake assigns to four transceivers, in slot order. */
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

    // --- the description is where the slot map comes from --------------------

    @Test
    fun `media section ids are read in m-line order, gaps and all`() {
        val sdp = """
            v=0
            m=audio 9 UDP/TLS/RTP/SAVPF 111
            a=mid:0
            m=video 0 UDP/TLS/RTP/SAVPF 96
            m=video 9 UDP/TLS/RTP/SAVPF 96
            a=mid:2
        """.trimIndent()

        assertEquals(listOf("0", null, "2"), sdpMids(sdp))
    }

    // --- the opener ----------------------------------------------------------

    @Test
    fun `the opener creates exactly four transceivers, audio video video audio`() = runTest {
        val connection = FakePeerConnection()
        val link = opener(connection, Recorder())

        link.openSlots(listOf(track(Roles.CAMERA)))

        assertEquals(
            listOf(SlotKind.AUDIO, SlotKind.VIDEO, SlotKind.VIDEO, SlotKind.AUDIO),
            connection.slotTransceivers,
            "mic, camera, screen, screen-audio: audio first so a pair whose video is refused still bundles its microphone on m-line zero",
        )
    }

    @Test
    fun `the opening offer carries the map from mid to slot role`() = runTest {
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val link = opener(connection, recorder)

        link.openSlots(emptyList())

        val offer = recorder.sent.single()
        assertEquals(SignalType.OFFER, offer.type)
        assertEquals(fourSlots, offer.slots)
        assertEquals(fourSlots, link.slotMap)
        assertEquals(4, link.slots?.size)
    }

    @Test
    fun `a track goes into the slot its role names`() = runTest {
        val connection = FakePeerConnection()
        val link = opener(connection, Recorder())

        link.openSlots(listOf(track(Roles.CAMERA), track(Roles.MIC)))

        assertEquals("track-${Roles.MIC}", connection.trackAt("0"))
        assertEquals("track-${Roles.CAMERA}", connection.trackAt("1"))
        assertEquals(SlotState.SENDING, link.slots?.state(Roles.CAMERA))
        assertEquals(SlotState.IDLE, link.slots?.state(Roles.SCREEN))
    }

    @Test
    fun `an opening offer that describes anything but four slots is refused`() = runTest {
        // Some other code path called addTrack on a slotted connection, which
        // would grow the m-lines for the life of the pair - the duplication
        // risk section 9 of the spec names. Better to fail loudly here than to
        // hand the far end a map with a hole in it.
        val connection = FakePeerConnection()
        connection.strayMediaSections = 1
        val recorder = Recorder()
        val link = opener(connection, recorder)

        assertFailsWith<IllegalStateException> { link.openSlots(emptyList()) }
        assertNull(link.slots)
        assertTrue(recorder.sent.isEmpty(), "nothing goes on the wire that the far end could not bind")
    }

    // --- H1, the whole point -------------------------------------------------

    @Test
    fun `camera, mic, share and screen-audio toggles produce no offer and no new m-line`() = runTest {
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val link = opener(connection, recorder)
        link.openSlots(emptyList())
        val afterOpening = recorder.sent.size

        link.applyTracks(listOf(track(Roles.CAMERA)))
        link.applyTracks(listOf(track(Roles.CAMERA), track(Roles.MIC)))
        link.applyTracks(listOf(track(Roles.MIC)))
        link.applyTracks(listOf(track(Roles.MIC), track(Roles.SCREEN), track(Roles.SCREEN_AUDIO)))
        link.applyTracks(listOf(track(Roles.MIC)))

        assertEquals(afterOpening, recorder.sent.size, "not one signal: there is nothing to renegotiate")
        assertEquals(4, connection.slotTransceivers.size)
        assertEquals(1, connection.localDescriptions.size, "and no second description either")
    }

    @Test
    fun `twenty toggles leave the m-line count where it started`() = runTest {
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val link = opener(connection, recorder)
        link.openSlots(emptyList())

        repeat(20) { turn ->
            link.applyTracks(if (turn % 2 == 0) listOf(track(Roles.CAMERA)) else emptyList())
        }

        assertEquals(4, connection.slotTransceivers.size)
        assertEquals(4, sdpMids(recorder.sent.single().sdp!!).size)
        assertEquals(20, connection.trackSwaps.count { it.first == "1" }, "twenty swaps in the one slot")
    }

    @Test
    fun `a microphone pipeline swap is one setTrack in the slot that already exists`() = runTest {
        // Adopting a processed microphone track over the raw one used to be a
        // renegotiation, which is what made a lost answer during a mic change
        // survivable only because mute is `enabled`, not a track change.
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val link = opener(connection, recorder)
        link.openSlots(listOf(SlotTrack(Roles.MIC, "raw")))

        link.applyTracks(listOf(SlotTrack(Roles.MIC, "processed")))

        assertEquals("processed", connection.trackAt("0"))
        assertEquals(SignalType.OFFER, recorder.types().single())
    }

    @Test
    fun `a refused audience holds null in every slot, so nothing leaves this device for them`() = runTest {
        // Audience narrowing arrives as an empty list and must mean the media
        // never leaves, not that it leaves muted. Emptying a slot is the
        // fixed-slot spelling of removing the sender.
        val connection = FakePeerConnection()
        val link = opener(connection, Recorder())
        link.openSlots(listOf(track(Roles.MIC), track(Roles.CAMERA)))

        link.applyTracks(emptyList())

        assertNull(connection.trackAt("0"))
        assertNull(connection.trackAt("1"))
        assertEquals(SlotState.IDLE, link.slots?.state(Roles.MIC))
        assertEquals(SlotState.IDLE, link.slots?.state(Roles.CAMERA))
    }

    @Test
    fun `a slot that refuses its track is marked broken and reported`() = runTest {
        // The only path in section 3.1 where a slot change still costs a
        // negotiation. Expected never to happen, which is exactly why it must
        // not be swallowed.
        val connection = FakePeerConnection()
        connection.refuseTrackAt = setOf("1")
        val link = opener(connection, Recorder())
        link.openSlots(emptyList())

        val refused = link.slots!!.apply(listOf(track(Roles.CAMERA), track(Roles.MIC)))

        assertEquals(listOf(Roles.CAMERA), refused)
        assertEquals(SlotState.BROKEN, link.slots?.state(Roles.CAMERA))
        assertEquals(SlotState.SENDING, link.slots?.state(Roles.MIC))
    }

    // --- the answerer --------------------------------------------------------

    @Test
    fun `the answerer binds by mid and creates no transceivers of its own`() = runTest {
        // Amendment A1: four slots opened with addTransceiver on the answering
        // side cannot absorb the offerer's four m-lines, so the far end makes
        // four more and the pair carries eight for the rest of the call.
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val link = answerer(connection, recorder)

        link.onRemoteSignal(
            SignalEnvelope(high, SignalType.OFFER, room, sdp = offeredSdp(), gen = 1, seq = 1, slots = fourSlots),
        )

        assertTrue(connection.slotTransceivers.isEmpty(), "not one of its own")
        assertEquals(fourSlots, link.slotMap)
        assertEquals(4, link.slots?.size)
        assertEquals(SignalType.ANSWER, recorder.types().single())
    }

    @Test
    fun `the answerer widens all four slots before it describes the answer`() = runTest {
        // The one window in which the direction can still be widened: a remote
        // offer creates its transceivers recvonly, and an answer may only ever
        // narrow what it describes, so a slot not widened here is a slot this
        // side can never send on for the life of the connection.
        val connection = FakePeerConnection()
        val link = answerer(connection, Recorder())

        link.onRemoteSignal(
            SignalEnvelope(high, SignalType.OFFER, room, sdp = offeredSdp(), gen = 1, seq = 1, slots = fourSlots),
        )

        assertEquals(listOf("0", "1", "2", "3"), connection.widened)
        assertNotNull(link.slots)
    }

    @Test
    fun `the answerer puts its own tracks in as it binds`() = runTest {
        val connection = FakePeerConnection()
        val link = answerer(connection, Recorder())
        link.applyTracks(listOf(track(Roles.MIC), track(Roles.CAMERA)))

        link.onRemoteSignal(
            SignalEnvelope(high, SignalType.OFFER, room, sdp = offeredSdp(), gen = 1, seq = 1, slots = fourSlots),
        )

        assertEquals("track-${Roles.MIC}", connection.trackAt("0"))
        assertEquals("track-${Roles.CAMERA}", connection.trackAt("1"))
    }

    @Test
    fun `the answer names the seq of the offer it answers`() = runTest {
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val link = answerer(connection, recorder)

        link.onRemoteSignal(
            SignalEnvelope(high, SignalType.OFFER, room, sdp = offeredSdp(), gen = 1, seq = 9, slots = fourSlots),
        )

        assertEquals(9L, recorder.sent.single().re, "an answer that crossed a retransmitted offer must be recognisable")
    }

    @Test
    fun `a slot map that does not name all four roles binds nothing`() = runTest {
        // Half a slot map is worse than none: this side would answer happily
        // and then never send on the slot it could not find.
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val link = answerer(connection, recorder)

        link.onRemoteSignal(
            SignalEnvelope(
                high,
                SignalType.OFFER,
                room,
                sdp = offeredSdp(),
                gen = 1,
                seq = 1,
                slots = mapOf("0" to Roles.MIC, "1" to Roles.CAMERA, "2" to Roles.SCREEN),
            ),
        )

        assertNull(link.slots)
        assertTrue(connection.widened.isEmpty())
        assertEquals(SignalType.ANSWER, recorder.types().single(), "the negotiation still completes; only the slots do not")
    }

    @Test
    fun `a slot map naming a role this build does not know is not enough on its own`() = runTest {
        val connection = FakePeerConnection()
        val link = answerer(connection, Recorder())

        link.onRemoteSignal(
            SignalEnvelope(
                high,
                SignalType.OFFER,
                room,
                sdp = offeredSdp(),
                gen = 1,
                seq = 1,
                slots = mapOf("0" to Roles.MIC, "1" to Roles.CAMERA, "2" to Roles.SCREEN, "3" to "hologram"),
            ),
        )

        assertNull(link.slots, "three known roles and one unknown is still three known roles")
    }

    // --- profiles stay apart -------------------------------------------------

    @Test
    fun `a profile-1 link never touches a slot`() = runTest {
        val connection = FakePeerConnection()
        val recorder = Recorder()
        // The polite side, so the collision below is resolved by a rollback and
        // both halves of an ordinary profile-1 negotiation are exercised.
        val link = PeerLink(low, high, connection, room, recorder::send)

        link.onNegotiationNeeded()
        link.onRemoteSignal(
            SignalEnvelope(high, SignalType.OFFER, room, sdp = offeredSdp(), gen = 1, seq = 1, slots = fourSlots),
        )

        assertNull(link.slots)
        assertTrue(connection.slotTransceivers.isEmpty())
        assertTrue(connection.widened.isEmpty())
        assertEquals(listOf(SignalType.OFFER, SignalType.ANSWER), recorder.types(), "and negotiates exactly as it always did")
    }

    @Test
    fun `a profile-2 link never offers because the connection asked it to`() = runTest {
        // The only negotiation a slotted connection ever starts is a generation
        // or an ICE restart, and both are explicit. Anything else is a code
        // path calling addTrack, which would grow the m-lines.
        val connection = FakePeerConnection()
        val recorder = Recorder()
        val link = opener(connection, recorder)
        link.openSlots(emptyList())

        link.onNegotiationNeeded()
        link.onNegotiationNeeded()

        assertEquals(1, recorder.sent.size, "the opening offer, and nothing else")
        assertEquals(2, link.unexpectedNegotiations)
    }

    @Test
    fun `the renegotiation libwebrtc raises for the four transceivers themselves is not counted`() = runTest {
        val connection = FakePeerConnection()
        val link = opener(connection, Recorder())

        link.onNegotiationNeeded()

        assertEquals(0, link.unexpectedNegotiations, "that one is ours; openSlots is about to make the offer")
    }

    // --- receive-side role resolution ---------------------------------------

    @Test
    fun `a mid names the slot it belongs to, on both sides`() = runTest {
        val connection = FakePeerConnection()
        val link = opener(connection, Recorder())
        link.openSlots(emptyList())
        val slots = link.slots!!

        assertEquals(Roles.MIC, slots.roleOf("0"))
        assertEquals(Roles.SCREEN_AUDIO, slots.roleOf("3"))
        assertNull(slots.roleOf("7"))
        assertNull(slots.roleOf(null))
        assertEquals("2", slots.midOf(Roles.SCREEN))
    }

    @Test
    fun `a batched ice signal applies every candidate it carries`() = runTest {
        // Section 6 step 6: batching must keep carrying only the candidate
        // string, which max-bundle is what lets Android rely on.
        val connection = FakePeerConnection()
        val link = answerer(connection, Recorder())
        link.onRemoteSignal(
            SignalEnvelope(high, SignalType.OFFER, room, sdp = offeredSdp(), gen = 1, seq = 1, slots = fourSlots),
        )

        link.onRemoteSignal(
            SignalEnvelope(
                high,
                SignalType.ICE,
                room,
                gen = 1,
                first = 2,
                seq = 4,
                candidates = listOf(
                    "candidate:1 1 udp 1 10.0.0.1 1 typ host",
                    "candidate:2 1 udp 1 10.0.0.2 2 typ host",
                    "candidate:3 1 udp 1 10.0.0.3 3 typ host",
                ),
            ),
        )

        assertEquals(3, connection.addedCandidates.size)
        assertEquals("candidate:3 1 udp 1 10.0.0.3 3 typ host", connection.addedCandidates.last().candidate)
    }
}
