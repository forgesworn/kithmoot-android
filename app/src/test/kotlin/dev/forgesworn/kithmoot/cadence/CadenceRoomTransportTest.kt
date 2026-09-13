package dev.forgesworn.kithmoot.cadence

import dev.forgesworn.kithmoot.protocol.CadenceReceipt
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

class CadenceRoomTransportTest {
    @Test fun boxOwnedEpochQueuesChatWhilePlainEventsAndLaterChatUseThePhone() = runTest {
        val deviceKey = ByteArray(32) { 7 }
        val room = "42".repeat(32)
        val lease = StoredCadenceLease(
            CadenceLeasePlan("a".repeat(52), room, dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(deviceKey), "22".repeat(16), 1,
                "11".repeat(16), "{}", 500002, 500004, 0, 8),
            CadenceOwnership.BOX_OWNED,
            CadenceReceipt("status", "22".repeat(16), 1, "active", 0, 500002, 500004, 0, emptyList(), emptyList()),
            0,
        )
        val phone = mutableListOf<Int>()
        val retained = mutableListOf<String>()
        val queued = mutableListOf<String>()
        val released = mutableListOf<String>()
        val inner = object : RoomTransport {
            override fun publish(event: dev.forgesworn.kithmoot.protocol.NostrEvent) { phone += event.kind }
            override suspend fun publishConfirmed(event: dev.forgesworn.kithmoot.protocol.NostrEvent, timeoutMs: Long): Boolean {
                phone += event.kind; return true
            }
            override fun subscribe(filters: List<Filter>) = emptyFlow<dev.forgesworn.kithmoot.protocol.NostrEvent>()
        }
        var now = 500002L * 3600
        val transport = CadenceRoomTransport(
            inner,
            backgroundScope,
            leaseAt = { epoch -> lease.takeIf { epoch in 500002 until 500004 } },
            retain = { event -> retained += event.id },
            queue = { _, event -> queued += event.id; CompletableFuture.completedFuture(true) },
            release = { released += it },
            now = { now },
        )
        val chat = Events.sign(deviceKey, 1460, now, listOf(listOf("d", room)), "cipher", ByteArray(32))
        assertTrue(transport.publishConfirmed(chat))
        transport.publish(Events.sign(deviceKey, 20461, now, listOf(listOf("d", room)), "roster", ByteArray(32)))
        assertEquals(listOf(chat.id), retained)
        assertEquals(listOf(chat.id), queued)
        assertEquals(listOf(chat.id), released)
        assertEquals(listOf(20461), phone)
        val failedRetained = mutableListOf<String>()
        val failedReleased = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val refusing = CadenceRoomTransport(
            inner,
            backgroundScope,
            leaseAt = { lease },
            retain = { failedRetained += it.id },
            queue = { _, _ -> CompletableFuture<Boolean>().also { it.completeExceptionally(IllegalStateException("reply lost")) } },
            release = { failedReleased += it },
            now = { now },
            onFailure = { failures += it },
        )
        val retainedEvent = Events.sign(deviceKey, 1460, now + 1, listOf(listOf("d", room)), "kept", ByteArray(32))
        assertFailsWith<IllegalStateException> { refusing.publishConfirmed(retainedEvent) }
        assertEquals(listOf(retainedEvent.id), failedRetained)
        assertEquals(emptyList<String>(), failedReleased)
        assertEquals(listOf("reply lost"), failures)
        now = 500004L * 3600
        assertTrue(transport.publishConfirmed(chat))
        assertEquals(listOf(20461, 1460), phone)
    }
}
