package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.RekeyNotice
import dev.forgesworn.kithmoot.protocol.decodeRekeyEvent
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.encodeRekeyEvent
import dev.forgesworn.kithmoot.protocol.decodeEpochRequest
import dev.forgesworn.kithmoot.protocol.encodeEpochGrant
import dev.forgesworn.kithmoot.protocol.KIND_ROSTER
import kotlinx.coroutines.launch
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RoomEpochTransitionTest {
    private val authoritySecret = Fixtures.key(41)
    private val authority = Schnorr.publicKeyHex(authoritySecret)

    @Test fun `a committed retained rekey moves all session traffic before announcing`() = runTest {
        val stable = Fixtures.room()
        val identity = Fixtures.primary(stable, 1, 2)
        val relay = FakeRelay()
        val applied = mutableListOf<String>()
        val live = session(
            stable, identity, relay, authority = authority,
            epochGate = { _, _ -> EpochGateResult.COMMITTED },
            onEpochApplied = { _, keys ->
                assertTrue(relay.publicationBlocked)
                applied += keys.id
            },
        )
        live.join()
        runCurrent()
        val current = deriveEpoch(RoomEpoch(0, ByteArray(32) { 7 }))
        val next = RoomEpoch(1, ByteArray(32) { 44 })
        val event = encodeRekeyEvent(
            stable.roomId, authoritySecret, current, next, listOf(identity.devicePubkey), emptyList(), 1,
            recipientNonces = mapOf(identity.devicePubkey to ByteArray(32) { 3 }),
            bodyNonce = ByteArray(32) { 4 }, auxRand = ByteArray(32) { 5 },
        )

        relay.publish(event)
        runCurrent()

        val successor = deriveEpoch(next)
        assertEquals(successor.id, live.epochKeys().id)
        assertEquals(listOf("begin", "rekey", "complete"), relay.transitionCalls)
        assertEquals(listOf(successor.id), applied)
        assertIs<RoomEpochState.Active>(live.epochState.value)
        assertNull(live.movedOn.value)
        live.sendChat("under the successor")
        assertEquals(successor.id, relay.published.last().tagValue("d"))
    }

    @Test fun `a pending cadence retirement keeps every room publication blocked`() = runTest {
        val stable = Fixtures.room()
        val identity = Fixtures.primary(stable, 1, 2)
        val relay = FakeRelay()
        val live = session(stable, identity, relay, authority = authority, epochGate = { _, _ -> EpochGateResult.PENDING })
        live.join()
        val current = deriveEpoch(RoomEpoch(0, ByteArray(32) { 7 }))
        val event = encodeRekeyEvent(
            stable.roomId, authoritySecret, current, RoomEpoch(1, ByteArray(32) { 45 }),
            listOf(identity.devicePubkey), emptyList(), 1,
        )
        relay.publish(event)
        runCurrent()

        assertIs<RoomEpochState.Updating>(live.epochState.value)
        assertTrue(relay.publicationBlocked)
        assertFailsWith<IllegalStateException> { live.sendChat("must wait") }
        assertEquals(current.id, live.epochKeys().id)
    }

    @Test fun `a committed removal is terminal and never reveals or enters the successor`() = runTest {
        val stable = Fixtures.room()
        val identity = Fixtures.primary(stable, 1, 2)
        val relay = FakeRelay()
        val live = session(stable, identity, relay, authority = authority, epochGate = { _, _ -> EpochGateResult.COMMITTED })
        live.join()
        val current = deriveEpoch(RoomEpoch(0, ByteArray(32) { 7 }))
        val event = encodeRekeyEvent(
            stable.roomId, authoritySecret, current, RoomEpoch(1, ByteArray(32) { 46 }),
            recipients = emptyList(), removed = listOf(identity.participant), now = 1,
        )
        assertNull(decodeRekeyEvent(event, stable.roomId, authority, current, identity.deviceSecretKey)?.secret)
        relay.publish(event)
        runCurrent()

        assertIs<RoomEpochState.Removed>(live.epochState.value)
        assertTrue(relay.publicationBlocked)
        assertFailsWith<IllegalStateException> { live.sendChat("cannot return") }
        assertEquals(listOf("begin"), relay.transitionCalls)
    }

    @Test fun `a device that missed a rekey catches up through the stable recovery channel`() = runTest {
        val stable = Fixtures.room()
        val identity = Fixtures.primary(stable, 1, 2)
        val relay = FakeRelay()
        val granted = RoomEpoch(2, ByteArray(32) { 48 })
        val committed = mutableListOf<RekeyNotice>()
        val live = session(
            stable, identity, relay, authority = authority, epochSettleMs = 1_500,
            epochGate = { _, notice -> committed += notice; EpochGateResult.COMMITTED },
            epochResponder = { event ->
                val request = decodeEpochRequest(event, stable.roomId, authoritySecret, 0) ?: return@session null
                encodeEpochGrant(
                    stable.roomId, authoritySecret, request.device, request.request, 0,
                    epoch = granted, removed = listOf("55".repeat(32)),
                )
            },
        )
        val joining = launch { live.join() }
        runCurrent()
        val missedCurrent = deriveEpoch(RoomEpoch(1, ByteArray(32) { 47 }))
        val ahead = encodeRekeyEvent(
            stable.roomId, authoritySecret, missedCurrent, granted,
            listOf(identity.devicePubkey), listOf("55".repeat(32)), 1,
        )

        relay.publish(ahead)
        runCurrent()

        assertEquals(2, live.epochKeys().epoch)
        assertTrue(committed.single().catchUp)
        assertEquals(0, relay.countOfKind(KIND_ROSTER))
        advanceTimeBy(1_500)
        runCurrent()
        joining.join()
        assertEquals(listOf("begin", "rekey", "complete"), relay.transitionCalls)
        live.sendChat("recovered")
        assertEquals(deriveEpoch(granted).id, relay.published.last().tagValue("d"))
    }

    @Test fun `a signed recovery refusal makes removal terminal`() = runTest {
        val stable = Fixtures.room()
        val identity = Fixtures.primary(stable, 1, 2)
        val relay = FakeRelay()
        val live = session(
            stable, identity, relay, authority = authority,
            epochGate = { _, _ -> EpochGateResult.COMMITTED },
            epochResponder = { event ->
                val request = decodeEpochRequest(event, stable.roomId, authoritySecret, 0) ?: return@session null
                encodeEpochGrant(
                    stable.roomId, authoritySecret, request.device, request.request, 0, refused = "removed",
                )
            },
        )
        live.join()
        val missedCurrent = deriveEpoch(RoomEpoch(1, ByteArray(32) { 49 }))
        relay.publish(encodeRekeyEvent(
            stable.roomId, authoritySecret, missedCurrent, RoomEpoch(2, ByteArray(32) { 50 }),
            listOf(identity.devicePubkey), emptyList(), 1,
        ))
        runCurrent()

        assertIs<RoomEpochState.Removed>(live.epochState.value)
        assertTrue(relay.publicationBlocked)
        assertFailsWith<IllegalStateException> { live.sendChat("cannot return") }
    }
}
