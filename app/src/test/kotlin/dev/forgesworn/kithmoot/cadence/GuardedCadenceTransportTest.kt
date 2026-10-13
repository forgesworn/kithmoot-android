package dev.forgesworn.kithmoot.cadence

import dev.forgesworn.kithmoot.protocol.CadenceReceipt
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.*
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GuardedCadenceTransportTest {
    private val at = 1_800_007_200L
    private val key = ByteArray(32) { 7 }
    private fun event() = Events.sign(key, 1460, at, listOf(listOf("d", "42".repeat(32))), "recording", ByteArray(32))
    private fun lease() = StoredCadenceLease(CadenceLeasePlan("a".repeat(52), "42".repeat(32), "42".repeat(32), 1,
        dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(key), "22".repeat(16), 1, "11".repeat(16), "{}", 500002, 500004, 0, 8),
        CadenceOwnership.BOX_OWNED, CadenceReceipt("status", "22".repeat(16), 1, "active", at, 500002, 500004,
            0, emptyList(), emptyList()), at)
    private class Receiver : RoomTransport {
        val received = mutableSetOf<String>()
        override fun publish(event: NostrEvent) = error("borrowed a phone counter")
        override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
            stillAllowed: () -> Boolean, timeoutMs: Long): Boolean = error("fell back to the phone")
        override fun receivedEventConfirmsPublication(eventId: String) = eventId in received
        override fun subscribe(filters: List<Filter>) = emptyFlow<NostrEvent>()
    }
    private class Handoff : GuardedCadenceHandoff {
        var queued = 0
        var confirmed = 0
        var revoked = 0
        var deadline: Long? = null
        override fun queue(lease: StoredCadenceLease, event: NostrEvent, publishBefore: Long,
            stillAllowed: () -> Boolean): CompletableFuture<Unit> {
            assertTrue(stillAllowed()); queued++; deadline = publishBefore
            return CompletableFuture.completedFuture(Unit)
        }
        override fun confirmed(event: NostrEvent) { confirmed++ }
        override fun revoked(event: NostrEvent) { revoked++ }
    }
    private fun TestScope.transport(receiver: Receiver, handoff: Handoff?, now: () -> Long = { at }) =
        CadenceRoomTransport(receiver, backgroundScope, leaseAt = { lease() },
            retain = { error("retained in ordinary quiet queue") }, queue = { _, _ -> error("ordinary node queue") },
            release = { error("queue ACK released recording") }, ownershipGeneration = { 0 }, now = now,
            guardedHandoff = handoff)

    @Test fun queueAckWaitsUntilTheExactAuthenticatedRelayEventArrives() = runTest {
        val receiver = Receiver(); val handoff = Handoff(); val event = event()
        val transport = transport(receiver, handoff)
        val result = async { transport.publishConfirmedBounded(event, 0, at + 60, { true }, 1_000) }
        runCurrent()
        assertEquals(1, handoff.queued)
        assertEquals(at + 60, handoff.deadline)
        assertFalse(result.isCompleted)
        receiver.received.add("ff".repeat(32)); advanceTimeBy(100); runCurrent()
        assertFalse(result.isCompleted)
        receiver.received.add(event.id); advanceTimeBy(50); runCurrent()
        assertTrue(result.await())
        assertEquals(1, handoff.confirmed)
        assertEquals(0, handoff.revoked)
    }

    @Test fun timeoutIsUncertainAndRetryCanReconcileWithoutAnotherQueueOffer() = runTest {
        val receiver = Receiver(); val handoff = Handoff(); val event = event()
        val transport = transport(receiver, handoff)
        val result = async { runCatching { transport.publishConfirmedBounded(event, 0, at + 60, { true }, 100) } }
        advanceUntilIdle()
        assertTrue(result.await().exceptionOrNull() is PublicationUnconfirmedException)
        assertEquals(1, handoff.queued)
        assertEquals(0, handoff.confirmed)
        assertEquals(0, handoff.revoked)
        receiver.received.add(event.id)
        assertTrue(transport.publishConfirmedBounded(event, 0, at + 60, { true }, 100))
        assertEquals(1, handoff.queued)
        assertEquals(1, handoff.confirmed)
    }

    @Test fun revokedSourceRequestsCancellationAndCannotCompleteFromAQueueAck() = runTest {
        val receiver = Receiver(); val handoff = Handoff(); val event = event()
        var allowed = true
        val transport = transport(receiver, handoff)
        val result = async { runCatching { transport.publishConfirmedBounded(event, 0, at + 60, { allowed }, 1_000) } }
        runCurrent(); allowed = false; advanceTimeBy(50); runCurrent()
        assertTrue(result.await().exceptionOrNull() is PublicationUnconfirmedException)
        assertEquals(1, handoff.revoked)
        assertEquals(0, handoff.confirmed)
    }

    @Test fun absentQualifiedHandoffAndExpiredDeadlineNeverOffer() = runTest {
        val receiver = Receiver(); val handoff = Handoff(); val event = event()
        assertTrue(runCatching { transport(receiver, null).publishConfirmedBounded(event, 0, at + 60, { true }, 100) }
            .exceptionOrNull() is PublicationNotOfferedException)
        assertTrue(runCatching { transport(receiver, handoff).publishConfirmedBounded(event, 0, at, { true }, 100) }
            .exceptionOrNull() is PublicationNotOfferedException)
        assertEquals(0, handoff.queued)
    }
}
