package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.cadence.CadenceRoomTransport
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.session.QuietTransport
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BoundedRoomTransportTest {
    @Test fun originalDeadlineCrossesCadenceQuietAndHybridWithoutAnOrdinaryGuardFallback() = runTest {
        var clock = 1L
        var capturedDeadline: Long? = null
        var capturedGuard: (() -> Boolean)? = null
        var offers = 0
        val internet = object : BoundedRoomTransport {
            override fun publish(event: NostrEvent) = error("unexpected unguarded publication")
            override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
                stillAllowed: () -> Boolean, timeoutMs: Long): Boolean = error("deadline was lost")
            override suspend fun publishConfirmedBounded(event: NostrEvent, generation: Long, publishBefore: Long,
                stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
                assertTrue(stillAllowed()); capturedDeadline = publishBefore; capturedGuard = stillAllowed
                offers++; return true
            }
            override fun subscribe(filters: List<Filter>) = emptyFlow<NostrEvent>()
        }
        val nearby = object : RoomTransport {
            override fun publish(event: NostrEvent) {}
            override fun subscribe(filters: List<Filter>) = emptyFlow<NostrEvent>()
        }
        val hybrid = HybridRoomTransport(nearby, internet) { clock }
        val key = ByteArray(32) { 7 }
        val member = Schnorr.publicKeyHex(key)
        val quiet = QuietTransport(hybrid, ByteArray(32) { 9 }, member, listOf(member), 0,
            backgroundScope, now = { clock }, ticking = false)
        val transport: RoomTransport = CadenceRoomTransport(quiet, backgroundScope,
            leaseAt = { null }, retain = { error("ordinary queue retained bounded event") },
            queue = { _, _ -> error("ordinary queue offered bounded event") }, release = {},
            ownershipGeneration = { 0 }, now = { clock })
        // The non-chat fast lane crosses every wrapper without spending a quiet counter.
        val event = Events.sign(key, 20461, clock, listOf(listOf("d", "42".repeat(32))), "roster", ByteArray(32))
        assertTrue(transport.publishConfirmedBounded(event, transport.publicationGeneration(), 10, { true }, 1_000))
        assertEquals(10L, capturedDeadline)
        assertTrue(requireNotNull(capturedGuard).invoke())
        clock = 10
        assertFalse(requireNotNull(capturedGuard).invoke())
        org.junit.Assert.assertThrows(PublicationNotOfferedException::class.java) {
            kotlinx.coroutines.runBlocking {
                transport.publishConfirmedBounded(event, transport.publicationGeneration(), 10, { true }, 1_000)
            }
        }
        assertEquals(1, offers)
    }

    @Test fun plainPublisherFallbackCallsTheActualGuardOverride() = runTest {
        var offered = false
        val transport: RoomTransport = object : RoomTransport {
            override fun publish(event: NostrEvent) = error("unexpected publication")
            override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
                stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
                offered = stillAllowed(); return offered
            }
            override fun subscribe(filters: List<Filter>) = emptyFlow<NostrEvent>()
        }
        val event = Events.sign(ByteArray(32) { 7 }, 1460, 1, emptyList(), "cipher", ByteArray(32))
        assertFalse(transport.publishConfirmedBounded(event, 0, 10, { false }, 1_000))
        assertFalse(offered)
    }
}
