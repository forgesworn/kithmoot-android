package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.RekeyNotice
import dev.forgesworn.kithmoot.protocol.decodeRekeyEvent
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.encodeRekeyEvent
import dev.forgesworn.kithmoot.protocol.encodeRosterEvent
import dev.forgesworn.kithmoot.protocol.TrackRef
import dev.forgesworn.kithmoot.protocol.decodeRosterEvent
import dev.forgesworn.kithmoot.protocol.RosterEntry
import dev.forgesworn.kithmoot.protocol.decodeEpochRequest
import dev.forgesworn.kithmoot.protocol.encodeEpochGrant
import dev.forgesworn.kithmoot.protocol.KIND_ROSTER
import dev.forgesworn.kithmoot.protocol.KIND_SIGNAL_WRAP
import dev.forgesworn.kithmoot.protocol.KIND_EPOCH_REQUEST
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.flow.Flow
import kotlin.test.assertFalse
import kotlinx.coroutines.launch
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RoomEpochTransitionTest {
    private val authoritySecret = Fixtures.key(41)
    private val authority = Schnorr.publicKeyHex(authoritySecret)

    /** Subscribe is synchronous even if collecting its flow is scheduled.
     * A mesh query registered during the block is permanently suppressed. */
    private class TrafficSubscriptions(private val relay: FakeRelay) : RoomTransport by relay.transport() {
        val registered = mutableListOf<Pair<Filter, Boolean>>()
        override fun subscribe(filters: List<Filter>): Flow<NostrEvent> {
            filters.filter { it.kinds?.any { kind -> kind in setOf(KIND_ROSTER, KIND_CHAT, KIND_SIGNAL_WRAP) } == true }
                .forEach { registered += it to relay.publicationBlocked }
            return relay.transport().subscribe(filters)
        }
    }

    @Test fun `successor traffic subscriptions register only after the verified transport barrier opens`() = runTest {
        val stable = Fixtures.room(); val identity = Fixtures.primary(stable, 1, 2)
        val relay = FakeRelay(); val transport = TrafficSubscriptions(relay)
        val live = session(stable, identity, relay, authority = authority, transport = transport,
            epochGate = { _, _ -> EpochGateResult.COMMITTED })
        try {
            live.join(); runCurrent(); transport.registered.clear()
            val next = RoomEpoch(1, ByteArray(32) { 44 })
            relay.publish(encodeRekeyEvent(stable.roomId, authoritySecret,
                deriveEpoch(RoomEpoch(0, ByteArray(32) { 7 })), next,
                listOf(identity.devicePubkey), emptyList(), 1))
            runCurrent()
            val successor = deriveEpoch(next)
            assertEquals(1, live.epochKeys().epoch)
            assertTrue(transport.registered.any { it.first.kinds?.contains(KIND_CHAT) == true })
            assertTrue(transport.registered.all { !it.second }, "Successor subscriptions must emit their mesh queries after the barrier")
            val chat = transport.registered.single { it.first.kinds?.contains(KIND_CHAT) == true }.first
            val roster = transport.registered.single { it.first.kinds?.contains(KIND_ROSTER) == true }.first
            assertEquals(successor.id, chat.tags.getValue("#d").first())
            assertEquals(listOf(successor.id), roster.tags.getValue("#d"))
            live.sendChat("under the verified queried successor")
            assertEquals(successor.id, relay.published.last().tagValue("d"))
        } finally { live.leave() }
    }

    @Test fun `join settled after a replayed successor opens the barrier before traffic subscriptions`() = runTest {
        val stable = Fixtures.room(); val identity = Fixtures.primary(stable, 1, 2)
        val relay = FakeRelay(); val transport = TrafficSubscriptions(relay)
        val live = session(stable, identity, relay, authority = authority, transport = transport,
            epochSettleMs = 1_500, epochGate = { _, _ -> EpochGateResult.COMMITTED })
        try {
            val joining = launch { live.join() }; runCurrent()
            val next = RoomEpoch(1, ByteArray(32) { 45 })
            relay.publish(encodeRekeyEvent(stable.roomId, authoritySecret,
                deriveEpoch(RoomEpoch(0, ByteArray(32) { 7 })), next,
                listOf(identity.devicePubkey), emptyList(), 1))
            runCurrent()
            assertEquals(1, live.epochKeys().epoch)
            assertTrue(relay.publicationBlocked)
            assertTrue(transport.registered.isEmpty())
            advanceTimeBy(1_500); runCurrent(); joining.join()
            val successor = deriveEpoch(next)
            assertTrue(transport.registered.any { it.first.kinds?.contains(KIND_CHAT) == true })
            assertTrue(transport.registered.all { !it.second })
            val chat = transport.registered.single { it.first.kinds?.contains(KIND_CHAT) == true }.first
            val roster = transport.registered.single { it.first.kinds?.contains(KIND_ROSTER) == true }.first
            assertEquals(successor.id, chat.tags.getValue("#d").first())
            assertEquals(listOf(successor.id), roster.tags.getValue("#d"))
            live.sendChat("settled under the verified queried successor")
            assertEquals(successor.id, relay.published.last().tagValue("d"))
        } finally { live.leave() }
    }

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

    @Test fun `a scheduled rekey moves the room quietly, and a removal after it is still announced`() = runTest {
        val stable = Fixtures.room()
        val identity = Fixtures.primary(stable, 1, 2)
        val relay = FakeRelay()
        lateinit var live: RoomSession
        val atGate = mutableListOf<RoomEpochState>()
        live = session(
            stable, identity, relay, authority = authority,
            epochGate = { _, _ -> atGate += live.epochState.value; EpochGateResult.COMMITTED },
        )
        live.join()
        runCurrent()
        val zero = deriveEpoch(RoomEpoch(0, ByteArray(32) { 7 }))
        val one = RoomEpoch(1, ByteArray(32) { 46 })
        relay.publish(encodeRekeyEvent(stable.roomId, authoritySecret, zero, one, listOf(identity.devicePubkey), emptyList(), 1, commit = true, scheduled = true))
        runCurrent()

        assertEquals(1, live.epochKeys().epoch)
        assertEquals(RoomEpochState.Updating(1, scheduled = true), atGate.single())
        assertEquals(RoomEpochState.Active(1, deriveEpoch(one).id, scheduled = true), live.epochState.value)
        live.sendChat("under the scheduled successor")
        assertEquals(deriveEpoch(one).id, relay.published.last().tagValue("d"))

        val two = RoomEpoch(2, ByteArray(32) { 47 })
        relay.publish(encodeRekeyEvent(stable.roomId, authoritySecret, deriveEpoch(one), two, listOf(identity.devicePubkey), listOf("55".repeat(32)), 2))
        runCurrent()

        assertEquals(RoomEpochState.Updating(2, scheduled = false), atGate.last())
        assertEquals(RoomEpochState.Active(2, deriveEpoch(two).id, scheduled = false), live.epochState.value)
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

    @Test fun `a delayed presence reply is discarded while a secure update blocks traffic`() = runTest {
        val stable = Fixtures.room()
        val identity = Fixtures.primary(stable, 1, 2)
        val remote = Fixtures.primary(stable, 3, 4)
        val relay = FakeRelay()
        val live = session(
            stable,
            identity,
            relay,
            authority = authority,
            timing = Fixtures.QUIET.copy(announceJitterMs = 1_000),
            epochGate = { _, _ -> EpochGateResult.PENDING },
        )
        live.join()
        val before = relay.countFrom(identity.devicePubkey, KIND_ROSTER)
        relay.publish(
            encodeRosterEvent(
                RosterEntry(remote.participant, remote.devicePubkey, remote.credential, updatedAt = 0),
                stable.roomId,
                stable.roomKey,
                remote.deviceSecretKey,
            ),
        )
        runCurrent()
        val current = deriveEpoch(RoomEpoch(0, ByteArray(32) { 7 }))
        relay.publish(
            encodeRekeyEvent(
                stable.roomId,
                authoritySecret,
                current,
                RoomEpoch(1, ByteArray(32) { 45 }),
                listOf(identity.devicePubkey),
                emptyList(),
                1,
            ),
        )
        runCurrent()
        assertTrue(relay.publicationBlocked)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(before, relay.countFrom(identity.devicePubkey, KIND_ROSTER))
    }

    @Test fun `a track change while a secure update blocks traffic is kept and not fatal`() = runTest {
        val stable = Fixtures.room()
        val identity = Fixtures.primary(stable, 1, 2)
        val relay = FakeRelay()
        val live = session(
            stable, identity, relay, authority = authority,
            epochGate = { _, _ -> EpochGateResult.PENDING },
        )
        live.join()
        runCurrent()
        val before = relay.countFrom(identity.devicePubkey, KIND_ROSTER)
        val current = deriveEpoch(RoomEpoch(0, ByteArray(32) { 7 }))
        relay.publish(
            encodeRekeyEvent(
                stable.roomId, authoritySecret, current, RoomEpoch(1, ByteArray(32) { 45 }),
                listOf(identity.devicePubkey), emptyList(), 1,
            ),
        )
        runCurrent()
        assertTrue(relay.publicationBlocked)
        // The engine calls this on every local track change, gate or no gate:
        // a camera toggle during a secure update is a kept fact, not a crash.
        live.setTracks(listOf(TrackRef("camera-1", Roles.CAMERA)))
        assertEquals(before, relay.countFrom(identity.devicePubkey, KIND_ROSTER))
    }

    @Test fun `a track change during a committed rekey is announced under the successor`() = runTest {
        val stable = Fixtures.room()
        val identity = Fixtures.primary(stable, 1, 2)
        val relay = FakeRelay()
        val tracks = listOf(TrackRef("camera-1", Roles.CAMERA))
        lateinit var live: RoomSession
        live = session(
            stable, identity, relay, authority = authority,
            epochGate = { _, _ -> EpochGateResult.COMMITTED },
            onEpochApplied = { _, _ ->
                // Between the old epoch and the new: the gate is shut.
                assertTrue(relay.publicationBlocked)
                live.setTracks(tracks)
            },
        )
        live.join()
        runCurrent()
        val current = deriveEpoch(RoomEpoch(0, ByteArray(32) { 7 }))
        val next = RoomEpoch(1, ByteArray(32) { 44 })
        relay.publish(
            encodeRekeyEvent(
                stable.roomId, authoritySecret, current, next, listOf(identity.devicePubkey), emptyList(), 1,
                recipientNonces = mapOf(identity.devicePubkey to ByteArray(32) { 3 }),
                bodyNonce = ByteArray(32) { 4 }, auxRand = ByteArray(32) { 5 },
            ),
        )
        runCurrent()

        val successor = deriveEpoch(next)
        assertEquals(successor.id, live.epochKeys().id)
        val announced = relay.published.last { it.kind == KIND_ROSTER }
        assertEquals(successor.id, announced.tagValue("d"))
        // Successor traffic is keyed by the epoch id; the credential still names the room.
        val entry = assertNotNull(
            decodeRosterEvent(announced, successor.id, successor.key, currentTime / 1000, credentialRoomId = stable.roomId),
        )
        assertEquals(tracks, entry.tracks)
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
                val request = decodeEpochRequest(event, stable.roomId, authoritySecret, stable.roomKey, 0) ?: return@session null
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
                val request = decodeEpochRequest(event, stable.roomId, authoritySecret, stable.roomKey, 0) ?: return@session null
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

    /** An authority somewhere else on the relay: answers every epoch request for [room] with [answer] while [answering]. */
    private fun kotlinx.coroutines.test.TestScope.authorityOnRelay(
        relay: FakeRelay,
        room: dev.forgesworn.kithmoot.protocol.Room,
        answering: () -> Boolean = { true },
        answer: (dev.forgesworn.kithmoot.protocol.EpochRequest) -> dev.forgesworn.kithmoot.protocol.NostrEvent,
    ) {
        backgroundScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            relay.transport().subscribe(listOf(Filter(kinds = listOf(KIND_EPOCH_REQUEST), tags = mapOf("#d" to listOf(room.roomId)))))
                .collect { event ->
                    if (!answering()) return@collect
                    val request = decodeEpochRequest(event, room.roomId, authoritySecret, room.roomKey, currentTime / 1000) ?: return@collect
                    relay.publish(answer(request))
                }
        }
    }

    @Test fun `told at admission the room is ahead, a device asks its authority before saying anything`() = runTest {
        val stable = Fixtures.room()
        val identity = Fixtures.primary(stable, 1, 2)
        val relay = FakeRelay()
        val granted = RoomEpoch(1, ByteArray(32) { 60 })
        authorityOnRelay(relay, stable) { request ->
            encodeEpochGrant(stable.roomId, authoritySecret, request.device, request.request, currentTime / 1000, epoch = granted)
        }
        val committed = mutableListOf<RekeyNotice>()
        val live = session(
            stable, identity, relay, authority = authority, expectedEpoch = 1, epochSettleMs = 1_500,
            epochGate = { _, notice -> committed += notice; EpochGateResult.COMMITTED },
        )

        live.join()
        // Told, it does not wait out the settle, and holds everything until answered.
        assertEquals(0L, currentTime)
        assertEquals(0, relay.countOfKind(KIND_ROSTER))
        runCurrent()

        assertEquals(1, relay.countOfKind(KIND_EPOCH_REQUEST))
        assertEquals(1, live.epochKeys().epoch)
        assertIs<RoomEpochState.Active>(live.epochState.value)
        assertTrue(committed.single().catchUp)
        val successor = deriveEpoch(granted).id
        assertTrue(relay.countOfKind(KIND_ROSTER) > 0)
        assertTrue(relay.published.filter { it.kind == KIND_ROSTER }.all { it.tagValue("d") == successor })
        live.sendChat("on the room everybody else is in")
        assertEquals(successor, relay.published.last().tagValue("d"))
    }

    @Test fun `told the room is ahead with nobody answering, the room says it needs recovery and a retry asks again`() = runTest {
        val stable = Fixtures.room()
        val identity = Fixtures.primary(stable, 1, 2)
        val relay = FakeRelay()
        var answering = false
        val granted = RoomEpoch(1, ByteArray(32) { 61 })
        authorityOnRelay(relay, stable, answering = { answering }) { request ->
            encodeEpochGrant(stable.roomId, authoritySecret, request.device, request.request, currentTime / 1000, epoch = granted)
        }
        val live = session(
            stable, identity, relay, authority = authority, expectedEpoch = 1,
            epochGate = { _, _ -> EpochGateResult.COMMITTED },
        )
        live.join()
        advanceTimeBy(30_001)
        runCurrent()

        val stuck = assertIs<RoomEpochState.RecoveryNeeded>(live.epochState.value)
        assertEquals(1, stuck.expectedEpoch)
        assertEquals(1, live.movedOn.value)
        assertEquals(0, relay.countOfKind(KIND_ROSTER))
        assertFailsWith<IllegalStateException> { live.sendChat("not under the old key") }

        answering = true
        live.retryEpoch()
        runCurrent()
        assertEquals(2, relay.countOfKind(KIND_EPOCH_REQUEST))
        assertEquals(1, live.epochKeys().epoch)
        assertIs<RoomEpochState.Active>(live.epochState.value)
        assertTrue(relay.countOfKind(KIND_ROSTER) > 0)
    }

    @Test fun `told nothing, a device asks once and follows an authority that has moved on`() = runTest {
        val stable = Fixtures.room()
        val identity = Fixtures.primary(stable, 1, 2)
        val relay = FakeRelay()
        val granted = RoomEpoch(2, ByteArray(32) { 62 })
        authorityOnRelay(relay, stable) { request ->
            encodeEpochGrant(stable.roomId, authoritySecret, request.device, request.request, currentTime / 1000, epoch = granted)
        }
        val committed = mutableListOf<RekeyNotice>()
        val live = session(
            stable, identity, relay, authority = authority, epochProbe = true,
            epochGate = { _, notice -> committed += notice; EpochGateResult.COMMITTED },
        )
        live.join()
        runCurrent()

        assertEquals(1, relay.countOfKind(KIND_EPOCH_REQUEST))
        assertEquals(2, live.epochKeys().epoch)
        assertTrue(committed.single().catchUp)
        assertIs<RoomEpochState.Active>(live.epochState.value)
        assertEquals(deriveEpoch(granted).id, relay.published.last { it.kind == KIND_ROSTER }.tagValue("d"))
    }

    @Test fun `told nothing, an unanswered or current answer changes nothing`() = runTest {
        val stable = Fixtures.room()
        val identity = Fixtures.primary(stable, 1, 2)
        val silentRelay = FakeRelay()
        val silent = session(
            stable, identity, silentRelay, authority = authority, epochProbe = true,
            epochGate = { _, _ -> error("nothing to commit") },
        )
        silent.join()
        advanceTimeBy(20_001)
        runCurrent()
        assertEquals(1, silentRelay.countOfKind(KIND_EPOCH_REQUEST))
        assertIs<RoomEpochState.Active>(silent.epochState.value)
        assertEquals(0, silent.epochKeys().epoch)
        assertFalse(silentRelay.publicationBlocked)

        val currentRelay = FakeRelay()
        authorityOnRelay(currentRelay, stable) { request ->
            encodeEpochGrant(stable.roomId, authoritySecret, request.device, request.request, currentTime / 1000,
                epoch = RoomEpoch(0, ByteArray(32) { 7 }))
        }
        val current = session(
            stable, identity, currentRelay, authority = authority, epochProbe = true,
            epochGate = { _, _ -> error("nothing to commit") },
        )
        current.join()
        runCurrent()
        assertIs<RoomEpochState.Active>(current.epochState.value)
        assertEquals(0, current.epochKeys().epoch)
        assertFalse(currentRelay.publicationBlocked)
    }

    @Test fun `told nothing, a refusal from the authority is final`() = runTest {
        val stable = Fixtures.room()
        val identity = Fixtures.primary(stable, 1, 2)
        val relay = FakeRelay()
        authorityOnRelay(relay, stable) { request ->
            encodeEpochGrant(stable.roomId, authoritySecret, request.device, request.request, currentTime / 1000, refused = "removed")
        }
        val live = session(
            stable, identity, relay, authority = authority, epochProbe = true,
            epochGate = { _, _ -> EpochGateResult.COMMITTED },
        )
        live.join()
        runCurrent()

        assertIs<RoomEpochState.Removed>(live.epochState.value)
        assertTrue(relay.publicationBlocked)
        assertFailsWith<IllegalStateException> { live.sendChat("cannot return") }
    }

    @Test fun `told nothing, the authority's 'unknown' is not a removal`() = runTest {
        val stable = Fixtures.room()
        val identity = Fixtures.primary(stable, 1, 2)
        val relay = FakeRelay()
        authorityOnRelay(relay, stable) { request ->
            encodeEpochGrant(stable.roomId, authoritySecret, request.device, request.request, currentTime / 1000, refused = "unknown")
        }
        val live = session(
            stable, identity, relay, authority = authority, epochProbe = true,
            epochGate = { _, _ -> error("nothing to commit") },
        )
        live.join()
        runCurrent()
        assertIs<RoomEpochState.Active>(live.epochState.value)
    }

    @Test fun `told the room is ahead, the authority's 'unknown' waits to be let in (kithmoot#207)`() = runTest {
        val stable = Fixtures.room()
        val identity = Fixtures.primary(stable, 1, 2)
        val relay = FakeRelay()
        authorityOnRelay(relay, stable) { request ->
            encodeEpochGrant(stable.roomId, authoritySecret, request.device, request.request, currentTime / 1000, refused = "unknown")
        }
        val live = session(
            stable, identity, relay, authority = authority, expectedEpoch = 1,
            epochGate = { _, _ -> error("nothing to commit") },
        )
        launch { live.join() }
        advanceTimeBy(120_000)
        runCurrent()
        val state = assertIs<RoomEpochState.RecoveryNeeded>(live.epochState.value)
        assertTrue(state.waitingToBeLetIn)
        assertEquals(WAITING_TO_BE_LET_IN, state.reason)
        assertTrue(relay.publicationBlocked)
        live.leave()
    }

    @Test fun `the authority device never asks itself`() = runTest {
        val stable = Fixtures.room()
        val identity = Fixtures.primary(stable, 1, 2)
        val relay = FakeRelay()
        val live = session(
            stable, identity, relay, authority = authority, epochProbe = true, expectedEpoch = 3,
            epochGate = { _, _ -> EpochGateResult.COMMITTED },
            epochResponder = { null },
        )
        live.join()
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(0, relay.countOfKind(KIND_EPOCH_REQUEST))
        assertIs<RoomEpochState.Active>(live.epochState.value)
    }
}
