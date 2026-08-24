package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.KIND_SIGNAL_WRAP
import dev.forgesworn.kithmoot.protocol.MAX_SIGNALS_PER_WINDOW
import dev.forgesworn.kithmoot.protocol.SIGNAL_MAX_AGE_SECONDS
import dev.forgesworn.kithmoot.protocol.SignalBody
import dev.forgesworn.kithmoot.protocol.TrackRef
import dev.forgesworn.kithmoot.protocol.UnwrappedSignal
import dev.forgesworn.kithmoot.protocol.wrapSignal
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RoomSessionTest {

    @Test
    fun `a role claim moves between a person's devices and both agree`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val owner = Fixtures.primary(room, 1, 2)
        val phone = Fixtures.secondary(room, owner, 30)

        val laptopSession = session(room, owner, relay)
        val phoneSession = session(room, phone, relay, seed = 11)
        laptopSession.join()
        advanceTimeBy(2_000)
        runCurrent()
        phoneSession.join()
        advanceTimeBy(2_000)
        runCurrent()

        laptopSession.claim(Roles.MIC)
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(laptopSession.localRoles.value.holdsMic)
        assertEquals(owner.devicePubkey, phoneSession.localRoles.value.micDevice)

        // Time has to move on, or the two claims share a second and the tiebreak
        // decides instead of the timestamp.
        advanceTimeBy(2_000)
        phoneSession.claim(Roles.MIC)
        advanceTimeBy(2_000)
        runCurrent()

        // Both devices reach the same answer independently, with nothing
        // coordinating them.
        assertTrue(phoneSession.localRoles.value.holdsMic)
        assertEquals(false, laptopSession.localRoles.value.holdsMic)
        assertEquals(phone.devicePubkey, laptopSession.localRoles.value.micDevice)
    }

    @Test
    fun `releasing a role gives it up`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val owner = Fixtures.primary(room, 1, 2)
        val laptopSession = session(room, owner, relay)
        laptopSession.join()
        advanceTimeBy(1_000)
        runCurrent()

        laptopSession.claim(Roles.MIC)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(laptopSession.localRoles.value.holdsMic)

        laptopSession.release(Roles.MIC)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(false, laptopSession.localRoles.value.holdsMic)
        assertEquals(null, laptopSession.localRoles.value.micDevice)
    }

    @Test
    fun `published tracks reach the room grouped under the person`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val owner = Fixtures.primary(room, 1, 2)
        val stranger = Fixtures.primary(room, 70, 71)

        val mine = session(room, owner, relay)
        val theirs = session(room, stranger, relay, seed = 11)
        mine.join()
        advanceTimeBy(1_000)
        runCurrent()
        theirs.join()
        advanceTimeBy(2_000)
        runCurrent()

        mine.setTracks(listOf(TrackRef("cam-1", Roles.CAMERA), TrackRef("screen-1", Roles.SCREEN)))
        advanceTimeBy(2_000)
        runCurrent()

        val me = theirs.participants.value.single { it.participant == owner.participant }
        assertEquals(setOf("cam-1", "screen-1"), me.tracks.map { it.trackId }.toSet())
    }

    @Test
    fun `chat round-trips and is attributed to the person`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val owner = Fixtures.primary(room, 1, 2)
        val stranger = Fixtures.primary(room, 70, 71)

        val mine = session(room, owner, relay)
        val theirs = session(room, stranger, relay, seed = 11)
        mine.join()
        advanceTimeBy(1_000)
        runCurrent()
        theirs.join()
        advanceTimeBy(2_000)
        runCurrent()

        mine.sendChat("  Room's open. Send the link.  ")
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(1, theirs.chat.value.size)
        assertEquals("Room's open. Send the link.", theirs.chat.value.single().body)
        assertEquals(owner.participant, theirs.chat.value.single().participant)

        // Our own copy is shown at once and de-duplicated against the relay's echo.
        assertEquals(1, mine.chat.value.size)
    }

    @Test
    fun `an empty chat line is not published`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val mine = session(room, Fixtures.primary(room, 1, 2), relay)
        mine.join()
        advanceTimeBy(1_000)
        runCurrent()

        mine.sendChat("   ")
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(0, relay.countOfKind(KIND_CHAT))
    }

    @Test
    fun `a signal from a device in the roster is delivered`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val owner = Fixtures.primary(room, 1, 2)
        val stranger = Fixtures.primary(room, 70, 71)

        val mine = session(room, owner, relay)
        val theirs = session(room, stranger, relay, seed = 11)
        val received = mutableListOf<UnwrappedSignal>()
        backgroundScope.launch { mine.signals.collect { received += it } }

        mine.join()
        advanceTimeBy(1_000)
        runCurrent()
        theirs.join()
        advanceTimeBy(2_000)
        runCurrent()

        theirs.sendSignal(owner.devicePubkey, SignalBody("offer", room.roomId, sdp = "v=0"))
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(1, received.size)
        assertEquals(stranger.devicePubkey, received.single().from)
        assertEquals("v=0", received.single().body.sdp)
    }

    @Test
    fun `a signal from a device that is not in the room is refused`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val owner = Fixtures.primary(room, 1, 2)

        val mine = session(room, owner, relay)
        val received = mutableListOf<UnwrappedSignal>()
        backgroundScope.launch { mine.signals.collect { received += it } }
        mine.join()
        advanceTimeBy(1_000)
        runCurrent()

        // A well-formed, correctly encrypted offer from somebody who never
        // announced themselves. Answering it would mean opening a peer
        // connection to a stranger who is not in the room.
        val outsider = Fixtures.key(90)
        relay.publish(
            wrapSignal(
                body = SignalBody("offer", room.roomId, sdp = "v=0"),
                senderSecretKey = outsider,
                recipientPubkey = owner.devicePubkey,
                // Stamped on the session's own clock: otherwise this is
                // refused for staleness and the test proves nothing about
                // whether the sender is in the room.
                createdAt = currentTime / 1000,
            ).wrap,
        )
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(0, received.size)
        assertTrue(relay.countOfKind(KIND_SIGNAL_WRAP) > 0, "the wrap really was published")
    }

    @Test
    fun `BUG (I5)- a replayed signal is delivered once, however many times a relay sends it`() = runTest {
        // Publishing to every relay means hearing the same wrap from every
        // relay, and a relay that means harm can send it again later.
        val room = Fixtures.room()
        val relay = FakeRelay()
        val owner = Fixtures.primary(room, 1, 2)
        val stranger = Fixtures.primary(room, 70, 71)

        val mine = session(room, owner, relay)
        val theirs = session(room, stranger, relay, seed = 11)
        val received = mutableListOf<UnwrappedSignal>()
        backgroundScope.launch { mine.signals.collect { received += it } }

        mine.join()
        advanceTimeBy(1_000)
        runCurrent()
        theirs.join()
        advanceTimeBy(2_000)
        runCurrent()

        val wrap = wrapSignal(
            body = SignalBody("offer", room.roomId, sdp = "v=0"),
            senderSecretKey = stranger.deviceSecretKey,
            recipientPubkey = owner.devicePubkey,
            createdAt = currentTime / 1000,
        ).wrap
        relay.publish(wrap)
        advanceTimeBy(1_000)
        runCurrent()
        relay.publish(wrap)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(1, received.size)
    }

    @Test
    fun `BUG (I5)- a stale signal is refused`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val owner = Fixtures.primary(room, 1, 2)
        val stranger = Fixtures.primary(room, 70, 71)

        val mine = session(room, owner, relay)
        val theirs = session(room, stranger, relay, seed = 11)
        val received = mutableListOf<UnwrappedSignal>()
        backgroundScope.launch { mine.signals.collect { received += it } }

        mine.join()
        advanceTimeBy(1_000)
        runCurrent()
        theirs.join()
        advanceTimeBy(2_000)
        runCurrent()

        // A wrap captured a minute ago and played back now.
        relay.publish(
            wrapSignal(
                body = SignalBody("offer", room.roomId, sdp = "v=0"),
                senderSecretKey = stranger.deviceSecretKey,
                recipientPubkey = owner.devicePubkey,
                createdAt = currentTime / 1000 - SIGNAL_MAX_AGE_SECONDS - 40,
            ).wrap,
        )
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(0, received.size)
        assertTrue(relay.countOfKind(KIND_SIGNAL_WRAP) > 0, "the wrap really was published")
    }

    @Test
    fun `BUG (I5)- one device cannot flood the room with signals`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val owner = Fixtures.primary(room, 1, 2)
        val stranger = Fixtures.primary(room, 70, 71)

        val mine = session(room, owner, relay)
        val theirs = session(room, stranger, relay, seed = 11)
        val received = mutableListOf<UnwrappedSignal>()
        backgroundScope.launch { mine.signals.collect { received += it } }

        mine.join()
        advanceTimeBy(1_000)
        runCurrent()
        theirs.join()
        advanceTimeBy(2_000)
        runCurrent()

        for (i in 0 until MAX_SIGNALS_PER_WINDOW + 25) {
            relay.publish(
                wrapSignal(
                    body = SignalBody("ice", room.roomId, candidate = "candidate:$i"),
                    senderSecretKey = stranger.deviceSecretKey,
                    recipientPubkey = owner.devicePubkey,
                    createdAt = currentTime / 1000,
                ).wrap,
            )
        }
        runCurrent()

        assertEquals(MAX_SIGNALS_PER_WINDOW, received.size)
    }

    @Test
    fun `a signal from our own other device is refused`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val owner = Fixtures.primary(room, 1, 2)
        val phone = Fixtures.secondary(room, owner, 30)

        val laptopSession = session(room, owner, relay)
        val phoneSession = session(room, phone, relay, seed = 11)
        val received = mutableListOf<UnwrappedSignal>()
        backgroundScope.launch { laptopSession.signals.collect { received += it } }
        laptopSession.join()
        advanceTimeBy(1_000)
        runCurrent()
        phoneSession.join()
        advanceTimeBy(2_000)
        runCurrent()

        phoneSession.sendSignal(owner.devicePubkey, SignalBody("offer", room.roomId, sdp = "v=0"))
        advanceTimeBy(1_000)
        runCurrent()

        // One PeerConnection per remote device, never to your own.
        assertEquals(0, received.size)
    }

    @Test
    fun `leaving releases the microphone`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val owner = Fixtures.primary(room, 1, 2)
        val stranger = Fixtures.primary(room, 70, 71)

        val mine = session(room, owner, relay)
        val theirs = session(room, stranger, relay, seed = 11)
        mine.join()
        advanceTimeBy(1_000)
        runCurrent()
        theirs.join()
        advanceTimeBy(2_000)
        runCurrent()
        mine.claim(Roles.MIC)
        mine.setTracks(listOf(TrackRef("mic-1", Roles.MIC)))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(owner.devicePubkey, theirs.participants.value.single { it.participant == owner.participant }.micDevice)

        mine.leave()
        advanceTimeBy(2_000)
        runCurrent()

        // The wire format has no departure message, so presence lingers until it
        // lapses - but the microphone and the tracks go immediately, which is
        // the part that would otherwise be audible.
        val me = theirs.participants.value.single { it.participant == owner.participant }
        assertEquals(null, me.micDevice)
        assertEquals(0, me.tracks.size)
    }

    @Test
    fun `a stale roster entry does not overwrite a newer one`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val owner = Fixtures.primary(room, 1, 2)
        val stranger = Fixtures.primary(room, 70, 71)

        val mine = session(room, owner, relay)
        val theirs = session(room, stranger, relay, seed = 11)
        mine.join()
        advanceTimeBy(1_000)
        runCurrent()
        theirs.join()
        advanceTimeBy(2_000)
        runCurrent()

        val staleEvent = relay.published.first { it.pubkey == stranger.devicePubkey }
        advanceTimeBy(5_000)
        theirs.setTracks(listOf(TrackRef("cam-1", Roles.CAMERA)))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, mine.participants.value.single { it.participant == stranger.participant }.tracks.size)

        // A relay replaying an old entry must not be able to undo a newer one.
        relay.publish(staleEvent)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, mine.participants.value.single { it.participant == stranger.participant }.tracks.size)
    }
}
