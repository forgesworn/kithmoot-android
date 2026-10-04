package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.MEETING_REPOST_SECONDS
import dev.forgesworn.kithmoot.protocol.MeetingPolicy
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.decodeMeetingOp
import dev.forgesworn.kithmoot.protocol.encodeHandOp
import dev.forgesworn.kithmoot.protocol.verifyMeetingPolicy
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

/** The host's side of meeting mode: what `RoomWork` signs and posts on the
 *  control channel when the device that made the room runs it as a meeting. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomWorkMeetingTest {
    private val room = "a".repeat(64)
    private val key = ByteArray(32) { 9 }
    private val authoritySk = ByteArray(32) { 3 }
    private val authority = Schnorr.publicKeyHex(authoritySk)
    private val host = PrimaryIdentity.create(room, 1000, 100, ByteArray(32) { 1 }, ByteArray(32) { 4 })
    private val guest = PrimaryIdentity.create(room, 1000, 100, ByteArray(32) { 2 }, ByteArray(32) { 5 })

    private class Fixture(val incoming: MutableSharedFlow<NostrEvent>, val sent: MutableList<NostrEvent>, val transport: RoomTransport)
    private fun fixture(): Fixture {
        val incoming = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 32)
        val sent = mutableListOf<NostrEvent>()
        return Fixture(incoming, sent, object : RoomTransport {
            override fun publish(event: NostrEvent) { sent.add(event) }
            override fun subscribe(filters: List<Filter>) = incoming
            override suspend fun queryStored(filters: List<Filter>, timeoutMs: Long) = emptyList<NostrEvent>()
            override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean { sent.add(event); return true }
        })
    }
    private val storage = object : AssignmentStorage { var value: String? = null; override suspend fun load() = value; override suspend fun save(encrypted: String) { value = encrypted } }

    private fun TestScope.work(f: Fixture, sk: ByteArray? = authoritySk, nowMs: () -> Long = { 200_000 }) =
        RoomWork(room, key, host, f.transport, storage, backgroundScope, now = { 200 }, nowMs = nowMs, authority = authority, authoritySecretKey = sk)

    /** The meeting policy carried by a posted control event, checked as every other device checks it. */
    private fun posted(event: NostrEvent): MeetingPolicy {
        val message = assertNotNull(decodeChatEvent(event, room, key, 200, channel = "control"))
        val signed = assertNotNull(decodeMeetingOp(message.body))
        assertTrue(verifyMeetingPolicy(room, signed.policy, signed.sig, authority))
        return signed.policy
    }

    @Test fun only_the_device_holding_the_authority_key_can_run_a_meeting() = runTest {
        for (sk in listOf(null, ByteArray(32) { 6 })) {
            val f = fixture()
            val work = work(f, sk)
            work.open()
            assertFalse(work.moderator)
            assertFailsWith<IllegalStateException> { work.setMeetingMode(true) }
            assertFailsWith<IllegalStateException> { work.setSpeaker(guest.participant, true) }
            assertTrue(f.sent.none { event -> decodeChatEvent(event, room, key, 200, channel = "control")?.let { decodeMeetingOp(it.body) } != null })
            assertNull(work.meeting.state.value.meeting)
            work.close()
        }
    }

    @Test fun meeting_mode_puts_the_host_on_the_stage_and_every_change_outranks_the_last() = runTest {
        val f = fixture()
        var clock = 200_000L
        val work = work(f) { clock }
        work.open()
        assertTrue(work.moderator)
        work.setMeetingMode(true)
        val on = posted(f.sent.last())
        assertEquals(MeetingPolicy(true, listOf(host.participant), 200_001), on, "on, then the host added: two versions on")
        assertEquals(on, work.meeting.state.value.meeting, "adopted here at once")
        // A clock that went back still moves the version on.
        clock = 1_000
        work.setSpeaker(guest.participant, true)
        val withGuest = posted(f.sent.last())
        assertEquals(listOf(host.participant, guest.participant).sorted(), withGuest.speakers)
        assertEquals(200_002, withGuest.version)
        work.setSpeaker(guest.participant, false)
        assertEquals(MeetingPolicy(true, listOf(host.participant), 200_003), posted(f.sent.last()))
        work.setMeetingMode(false)
        assertEquals(MeetingPolicy(false, listOf(host.participant), 200_004), work.meeting.state.value.meeting)
        work.close()
    }

    @Test fun a_raised_hand_comes_down_when_its_owner_is_made_a_speaker() = runTest {
        val f = fixture()
        val work = work(f)
        work.open()
        work.setMeetingMode(true)
        f.incoming.emit(encodeChatEvent(encodeHandOp(true), guest.participant, guest.credential, room, key, guest.deviceSecretKey, 200, channel = "control", credentialRoomId = room))
        runCurrent()
        assertEquals(setOf(guest.participant), work.meeting.state.value.hands.keys)
        work.setSpeaker(guest.participant, true)
        assertTrue(work.meeting.state.value.hands.isEmpty())
        work.close()
    }

    @Test fun a_meeting_that_is_on_is_posted_again_and_one_that_is_off_is_not() = runTest {
        val f = fixture()
        val work = work(f)
        work.open()
        work.setMeetingMode(true)
        val first = f.sent.size
        advanceTimeBy(MEETING_REPOST_SECONDS * 1000 + 1)
        assertEquals(first + 1, f.sent.size)
        assertEquals(work.meeting.state.value.meeting, posted(f.sent.last()), "the very same signed policy")
        work.setMeetingMode(false)
        val afterOff = f.sent.size
        advanceTimeBy(3 * MEETING_REPOST_SECONDS * 1000)
        assertEquals(afterOff, f.sent.size)
        work.close()
    }
}
