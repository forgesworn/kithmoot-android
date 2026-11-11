package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.protocol.CallBell
import dev.forgesworn.kithmoot.protocol.CallBellState
import dev.forgesworn.kithmoot.protocol.CallMembership
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BackgroundBellOutcomeTest {
    private val watch = BackgroundRoomWatch(
        stableRoomId = "room-a",
        roomName = "Kitchen table",
        bellKey = ByteArray(32),
        relays = listOf("wss://relay.example"),
        selfParticipant = "participant-self",
        selfDevice = "device-self",
    )
    private val call = CallMembership("c0ffeec0ffeec0ffeec0ffeec0ffeec0", since = 100)

    @Test
    fun `a bell this device rang itself is ignored`() {
        val bell = CallBell(CallBellState.START, call, device = watch.selfDevice, createdAt = 100)

        assertEquals(BellOutcome.Ignore, outcomeFor(bell, watch, participant = null))
    }

    @Test
    fun `a start bell from anybody else rings, with the resolved caller when known`() {
        val bell = CallBell(CallBellState.START, call, device = "device-other", createdAt = 100)

        val resolved = outcomeFor(bell, watch, participant = "participant-other")
        assertIs<BellOutcome.Ring>(resolved)
        assertEquals(call.id, resolved.callId)
        assertEquals("participant-other", resolved.caller)
        // The call's own start, so the ring window counts from it.
        assertEquals(call.since, resolved.since)

        // Never a key, and never the room twice: the notification names the room.
        val unresolved = outcomeFor(bell, watch, participant = null)
        assertIs<BellOutcome.Ring>(unresolved)
        assertEquals("Somebody", unresolved.caller)
    }

    @Test
    fun `an end bell stops the ring regardless of who is resolved`() {
        val bell = CallBell(CallBellState.END, call, device = "device-other", createdAt = 100)

        assertEquals(BellOutcome.Stop(watch), outcomeFor(bell, watch, participant = "participant-other"))
        assertEquals(BellOutcome.Stop(watch), outcomeFor(bell, watch, participant = null))
    }

    @Test
    fun `a call another of this person's devices started does not ring here`() {
        val bell = CallBell(CallBellState.START, call, device = "device-laptop", createdAt = 100, participant = watch.selfParticipant)

        assertEquals(BellOutcome.Ignore, outcomeFor(bell, watch, participant = bell.participant))
    }

    @Test
    fun `an answer on another of this person's devices stops the ring for that call as answered elsewhere`() {
        val bell = CallBell(CallBellState.ANSWER, call, device = "device-laptop", createdAt = 100, participant = watch.selfParticipant)

        assertEquals(BellOutcome.AnsweredElsewhere(watch, call.id), outcomeFor(bell, watch, participant = watch.selfParticipant))
    }

    @Test
    fun `an answer from somebody else changes nothing`() {
        val bell = CallBell(CallBellState.ANSWER, call, device = "device-other", createdAt = 100, participant = "participant-other")

        assertEquals(BellOutcome.Ignore, outcomeFor(bell, watch, participant = "participant-other"))
    }

    @Test
    fun `an answer nobody can attribute changes nothing`() {
        // An older sender's bell with no credential, from a device this phone
        // has never seen: it might be anybody, so it stops nothing.
        val bell = CallBell(CallBellState.ANSWER, call, device = "device-unknown", createdAt = 100)

        assertEquals(BellOutcome.Ignore, outcomeFor(bell, watch, participant = null))
    }

    @Test
    fun `a fresh install migrates the ring switch to on and remembers it`() {
        var written: Boolean? = null
        val result = migrateBackgroundRingEnabled(hasStoredValue = false, storedValue = false) { written = it }

        assertEquals(true, result)
        assertEquals(true, written)
    }

    @Test
    fun `an install that explicitly chose off keeps it off, and nothing is rewritten`() {
        var written: Boolean? = null
        val result = migrateBackgroundRingEnabled(hasStoredValue = true, storedValue = false) { written = it }

        assertEquals(false, result)
        assertEquals(null, written)
    }

    @Test
    fun `an install that explicitly chose on keeps it on`() {
        var written: Boolean? = null
        val result = migrateBackgroundRingEnabled(hasStoredValue = true, storedValue = true) { written = it }

        assertEquals(true, result)
        assertEquals(null, written)
    }
}
