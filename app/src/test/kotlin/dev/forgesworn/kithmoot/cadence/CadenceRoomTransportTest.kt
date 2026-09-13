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
        val queued = mutableListOf<String>()
        val inner = object : RoomTransport {
            override fun publish(event: dev.forgesworn.kithmoot.protocol.NostrEvent) { phone += event.kind }
            override suspend fun publishConfirmed(event: dev.forgesworn.kithmoot.protocol.NostrEvent, timeoutMs: Long): Boolean {
                phone += event.kind; return true
            }
            override fun subscribe(filters: List<Filter>) = emptyFlow<dev.forgesworn.kithmoot.protocol.NostrEvent>()
        }
        var now = 500002L * 3600
        val transport = CadenceRoomTransport(inner, backgroundScope, { epoch -> lease.takeIf { epoch in 500002 until 500004 } }, { _, event ->
            queued += event.id; CompletableFuture.completedFuture(true)
        }, { now })
        val chat = Events.sign(deviceKey, 1460, now, listOf(listOf("d", room)), "cipher", ByteArray(32))
        assertTrue(transport.publishConfirmed(chat))
        transport.publish(Events.sign(deviceKey, 20461, now, listOf(listOf("d", room)), "roster", ByteArray(32)))
        assertEquals(listOf(chat.id), queued)
        assertEquals(listOf(20461), phone)
        now = 500004L * 3600
        assertTrue(transport.publishConfirmed(chat))
        assertEquals(listOf(20461, 1460), phone)
    }
}
