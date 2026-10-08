package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.CallMembership
import dev.forgesworn.kithmoot.protocol.KIND_ROSTER
import dev.forgesworn.kithmoot.protocol.MAX_CONFERENCE_SECONDS
import dev.forgesworn.kithmoot.protocol.SignalBody
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import java.util.Locale
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConferenceRoomsTest {
    private val london = ZoneId.of("Europe/London")

    @Test fun `the end reads as a short day and time`() {
        // Saturday 4 October 2025, 18:00 in London (17:00 UTC, summer time).
        val ends = 1_759_597_200L
        assertEquals("Sat 4 Oct, 18:00", conferenceEndLabel(ends, london, Locale.UK))
        assertEquals("Ends Sat 4 Oct, 18:00", conferenceEndsLine(ends, london, Locale.UK))
        assertEquals("This conference room ended on Sat 4 Oct, 18:00.", conferenceEndedMessage(ends, london, Locale.UK))
    }

    @Test fun `a length gives an end, and Never gives none`() {
        val now = 1_800_000_000L
        assertNull(ConferenceLength.NEVER.endsFrom(now))
        assertEquals(now + 86_400, ConferenceLength.ONE_DAY.endsFrom(now))
        assertEquals(now + 3 * 86_400, ConferenceLength.THREE_DAYS.endsFrom(now))
        assertEquals(now + 7 * 86_400, ConferenceLength.SEVEN_DAYS.endsFrom(now))
        assertTrue(ConferenceLength.entries.all { (it.endsFrom(now) ?: now) - now <= MAX_CONFERENCE_SECONDS })
    }

    @Test fun `custom lifetimes preserve minute precision and enforce the protocol maximum`() {
        val now = 1_800_000_000L
        assertEquals(now + 60, ConferenceLength.CUSTOM.endsFrom(now, 60))
        assertEquals(now + 86400 + 2 * 3600 + 5 * 60, ConferenceLength.CUSTOM.endsFrom(now, 86400 + 2 * 3600 + 5 * 60))
        assertEquals(now + MAX_CONFERENCE_SECONDS, ConferenceLength.CUSTOM.endsFrom(now, MAX_CONFERENCE_SECONDS.toInt()))
        assertFailsWith<IllegalArgumentException> { ConferenceLength.CUSTOM.endsFrom(now, 0) }
        assertFailsWith<IllegalArgumentException> { ConferenceLength.CUSTOM.endsFrom(now, MAX_CONFERENCE_SECONDS.toInt() + 60) }
    }

    @Test fun `every event a conference session publishes expires no later than the end`() = runTest {
        val ends = 10_000L
        val room = Fixtures.room()
        val relay = FakeRelay()
        val alice = session(room, Fixtures.primary(room, 1, 2), relay, ends = ends)
        val bob = session(room, Fixtures.primary(room, 3, 4), relay, seed = 11, ends = ends)
        alice.join(); bob.join()
        advanceTimeBy(2_000); runCurrent()
        alice.sendChat("Welcome to the conference")
        alice.setCall(CallMembership("ab".repeat(16), 1))
        alice.sendSignal(bob.identity.devicePubkey, SignalBody(type = "offer", roomId = room.roomId, sdp = "v=0"))
        alice.leave()
        advanceTimeBy(2_000); runCurrent()

        assertTrue(relay.published.size >= 5)
        for (event in relay.published) {
            val expirations = event.tags.filter { it[0] == "expiration" }
            assertEquals(1, expirations.size, "kind ${event.kind} must carry exactly one expiration")
            assertTrue(expirations.single()[1].toLong() <= ends, "kind ${event.kind} must lapse by the end")
        }
        // Tagged events are still the room's: the other member reads them.
        assertEquals("Welcome to the conference", bob.chat.value.single().body)
    }

    @Test fun `a deadline learned while open binds later traffic and cannot be extended`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val alice = session(room, Fixtures.primary(room, 1, 2), relay)
        alice.join(); advanceTimeBy(100); runCurrent()
        alice.learnRoomEnd(100)
        alice.learnRoomEnd(10_000)
        relay.published.clear()
        alice.sendChat("Expiry learned from the Mac")
        runCurrent()
        assertTrue(relay.published.any { it.kind == KIND_CHAT })
        assertTrue(relay.published.filter { it.kind == KIND_CHAT }.all { it.tagValue("expiration") == "100" })
        advanceTimeBy(101_000); runCurrent()
        relay.published.clear()
        assertFailsWith<IllegalStateException> { alice.sendChat("Too late") }
        runCurrent()
        assertTrue(relay.published.none { it.kind == KIND_CHAT })
    }

    @Test fun `a room without an end publishes its roster and chat untagged`() = runTest {
        val room = Fixtures.room()
        val relay = FakeRelay()
        val alice = session(room, Fixtures.primary(room, 1, 2), relay)
        alice.join(); advanceTimeBy(100); runCurrent()
        alice.sendChat("Hello")
        runCurrent()
        assertTrue(relay.published.any { it.kind == KIND_ROSTER })
        assertTrue(relay.published.filter { it.kind == KIND_ROSTER || it.kind == KIND_CHAT }.none { e -> e.tags.any { it[0] == "expiration" } })
    }
}
