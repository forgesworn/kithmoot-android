package dev.forgesworn.kithmoot.cadence

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.CadenceReceipt
import dev.forgesworn.kithmoot.relay.PublicationNotOfferedException
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CadenceGuardedPublicationTest {
    private val device = ByteArray(32) { 7 }
    private var now = 500002L * 3600
    private var ownership = 0L
    private var lease: StoredCadenceLease? = null
    private var retained = 0
    private var handedOff = 0
    private var released = 0

    private fun event(kind: Int = 1460) = Events.sign(device, kind, now,
        listOf(listOf("d", "42".repeat(32))), "cipher", ByteArray(32))

    private fun TestScope.transport(inner: RoomTransport) = CadenceRoomTransport(inner,
        backgroundScope, leaseAt = { epoch -> lease?.takeIf { epoch in it.plan.startEpoch until it.plan.endEpoch } },
        retain = { retained++ }, queue = { _, _ -> handedOff++; CompletableFuture.completedFuture(true) },
        release = { released++ }, ownershipGeneration = { ownership }, now = { now })

    private fun excluded() = StoredCadenceLease(CadenceLeasePlan("a".repeat(52),
        "42".repeat(32), "42".repeat(32), 1,
        dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(device), "22".repeat(16), 1,
        "11".repeat(16), "{}", 500002, 500004, 0, 8), CadenceOwnership.CLIENT_EXCLUDED, null, 0)

    @Test fun `phone owned guarded publication forwards the original token timeout and event`() = runTest {
        val relay = FakeRelay()
        val delegate = relay.transport()
        var receivedToken = -1L
        var receivedTimeout = -1L
        var received: NostrEvent? = null
        val inner = object : RoomTransport by delegate {
            override fun publicationGeneration() = 42L
            override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
                stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
                received = event; receivedToken = generation; receivedTimeout = timeoutMs
                if (generation != publicationGeneration() || !stillAllowed()) throw PublicationNotOfferedException()
                return delegate.publishConfirmed(event, timeoutMs)
            }
        }
        val a = transport(inner)
        val message = event()
        assertEquals(42L, a.publicationGeneration())
        assertTrue(a.publishConfirmedGuarded(message, a.publicationGeneration(), { true }, 1234))
        assertEquals(message, received)
        assertEquals(42L, receivedToken)
        assertEquals(1234L, receivedTimeout)
        assertEquals(listOf(message), relay.published)
        assertEquals(0, retained + handedOff + released)
    }

    @Test fun `delegated or unresolved ownership cannot substitute a box queue receipt`() = runTest {
        val relay = FakeRelay()
        val a = transport(relay.transport())
        for (state in listOf(CadenceOwnership.CLIENT_EXCLUDED, CadenceOwnership.BOX_OWNED)) {
            lease = excluded().let { excluded -> if (state == CadenceOwnership.BOX_OWNED)
                excluded.copy(ownership = state, receipt = CadenceReceipt("status", excluded.plan.leaseId,
                    1, "active", 0, 500002, 500004, 0, emptyList(), emptyList())) else excluded }
            assertFailsWith<PublicationNotOfferedException> {
                a.publishConfirmedGuarded(event(), a.publicationGeneration(), { true })
            }
        }
        assertTrue(relay.published.isEmpty())
        assertEquals(0, retained + handedOff + released)
    }

    @Test fun `ownership change at actual dispatch prevents the phone from borrowing node slots`() = runTest {
        val relay = FakeRelay()
        val delegate = relay.transport()
        var reads = 0
        val inner = object : RoomTransport by delegate {
            override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
                stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
                lease = excluded()
                ownership++
                return delegate.publishConfirmedGuarded(event, generation, stillAllowed, timeoutMs)
            }
        }
        val a = CadenceRoomTransport(inner, backgroundScope,
            leaseAt = { reads++; lease }, retain = { retained++ },
            queue = { _, _ -> handedOff++; CompletableFuture.completedFuture(true) },
            release = { released++ }, ownershipGeneration = { ownership }, now = { now })
        assertFailsWith<PublicationNotOfferedException> {
            a.publishConfirmedGuarded(event(), a.publicationGeneration(), { true })
        }
        assertEquals(1, reads) // Dispatch reads only the cheap revision, never the vault.
        assertTrue(relay.published.isEmpty())
        assertEquals(0, retained + handedOff + released)
    }

    @Test fun `a wait crossing an ownership epoch cannot dispatch under its earlier phone snapshot`() = runTest {
        val relay = FakeRelay()
        val delegate = relay.transport()
        val inner = object : RoomTransport by delegate {
            override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
                stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
                now += 3600
                return delegate.publishConfirmedGuarded(event, generation, stillAllowed, timeoutMs)
            }
        }
        val a = transport(inner)
        assertFailsWith<PublicationNotOfferedException> {
            a.publishConfirmedGuarded(event(), a.publicationGeneration(), { true })
        }
        assertTrue(relay.published.isEmpty())
    }

    @Test fun `caller revocation reaches dispatch and explicit refusal remains false`() = runTest {
        val relay = FakeRelay()
        val a = transport(relay.transport())
        assertFailsWith<PublicationNotOfferedException> {
            a.publishConfirmedGuarded(event(), a.publicationGeneration(), { false })
        }
        relay.confirmsPublications = false
        assertFalse(a.publishConfirmedGuarded(event(), a.publicationGeneration(), { true }))
        assertTrue(relay.published.isEmpty())
    }

    @Test fun `non chat guarded controls stay on their normal lane during delegation`() = runTest {
        val relay = FakeRelay()
        lease = excluded()
        val a = transport(relay.transport())
        val control = event(20461)
        assertTrue(a.publishConfirmedGuarded(control, a.publicationGeneration(), { true }))
        assertEquals(listOf(control), relay.published)
        assertEquals(0, retained + handedOff + released)
    }
}
