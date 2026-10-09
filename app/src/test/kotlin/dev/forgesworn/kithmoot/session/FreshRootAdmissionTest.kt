package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class FreshRootAdmissionTest {
    private val root = Fixtures.key(41)
    private val authority = Schnorr.publicKeyHex(root)
    private val secret = ByteArray(32) { 7 }

    @Test fun `fresh root epoch zero precedes presence and permits both way chat`() = runTest {
        val room = Fixtures.room(); val relay = FakeRelay()
        val peer = session(room, Fixtures.primary(room, 3, 4), relay)
        peer.join()
        val client = session(room, Fixtures.primary(room, 1, 2), relay, authority = authority,
            epochGate = { _, _ -> EpochGateResult.COMMITTED }, requireFreshEpoch = true)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            relay.transport().subscribe(listOf(Filter(kinds = listOf(KIND_EPOCH_REQUEST)))).collect { request ->
                assertFalse(relay.published.any { it.kind == KIND_ROSTER && it.pubkey == client.identity.devicePubkey })
                relay.publish(encodeEpochGrant(room.roomId, root, request.pubkey, request.id, currentTime / 1000, RoomEpoch(0, secret)))
            }
        }
        val joining = async { client.join() }; runCurrent(); joining.await()
        val kinds = relay.published.filter { it.pubkey == client.identity.devicePubkey }.map { it.kind }
        assertEquals(KIND_EPOCH_REQUEST, kinds.first())
        assertTrue(KIND_ROSTER in kinds)
        client.sendChat("fresh client"); peer.sendChat("root route reply"); runCurrent()
        assertTrue(peer.chat.value.any { it.body == "fresh client" })
        assertTrue(client.chat.value.any { it.body == "root route reply" })
    }

    @Test fun `epoch zero silence never opens presence or chat`() = runTest {
        val room = Fixtures.room(); val relay = FakeRelay().apply { replays = true }
        val client = session(room, Fixtures.primary(room, 1, 2), relay, authority = authority,
            epochGate = { _, _ -> EpochGateResult.COMMITTED }, requireFreshEpoch = true, freshEpochTimeoutMs = 600)
        val joining = async { runCatching { client.join() } }
        runCurrent(); advanceTimeBy(601); runCurrent()
        assertTrue(joining.await().isFailure)
        assertEquals(3, relay.published.size)
        assertTrue(relay.published.all { it.kind == KIND_EPOCH_REQUEST })
        assertEquals(3, relay.published.map { it.id }.distinct().size)
        assertFailsWith<IllegalStateException> { client.sendChat("blocked") }
    }

    @Test fun `two lost grants cause fresh bounded requests and a newer epoch is committed before traffic`() = runTest {
        val room = Fixtures.room(); val relay = FakeRelay(); var requests = 0; var commits = 0
        val client = session(room, Fixtures.primary(room, 1, 2), relay, authority = authority,
            epochGate = { _, _ -> commits++; EpochGateResult.COMMITTED }, expectedEpoch = 1,
            requireFreshEpoch = true, freshEpochTimeoutMs = 600)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            relay.transport().subscribe(listOf(Filter(kinds = listOf(KIND_EPOCH_REQUEST)))).collect { request ->
                if (++requests == 3) relay.publish(encodeEpochGrant(room.roomId, root, request.pubkey, request.id,
                    currentTime / 1000, RoomEpoch(1, ByteArray(32) { 9 })))
            }
        }
        val joining = async { client.join() }; runCurrent(); advanceTimeBy(401); runCurrent(); joining.await()
        assertEquals(1, client.epochKeys().epoch); assertEquals(1, commits)
        assertEquals(3, requests)
        assertEquals(3, relay.published.filter { it.kind == KIND_EPOCH_REQUEST }.map { it.id }.distinct().size)
        client.sendChat("new epoch")
        assertEquals(client.epochKeys().id, relay.published.last().tagValue("d"))
    }

    @Test fun `removed unknown stale and superseded grants never open a fresh gate`() = runTest {
        for (mode in listOf("removed", "unknown", "stale", "superseded")) {
            val room = Fixtures.room(); val relay = FakeRelay(); var held: NostrEvent? = null
            val client = session(room, Fixtures.primary(room, 1, 2), relay, authority = authority,
                epochGate = { _, _ -> EpochGateResult.COMMITTED }, expectedEpoch = if (mode == "stale") 1 else 0,
                requireFreshEpoch = true, freshEpochTimeoutMs = 600)
            val desk = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                relay.transport().subscribe(listOf(Filter(kinds = listOf(KIND_EPOCH_REQUEST)))).collect { request ->
                    val event = encodeEpochGrant(room.roomId, root, request.pubkey, request.id, currentTime / 1000,
                        epoch = if (mode in listOf("stale", "superseded")) RoomEpoch(0, secret) else null,
                        refused = mode.takeIf { it in listOf("removed", "unknown") })
                    if (mode != "superseded") relay.publish(event)
                    else if (held == null) held = event else relay.publish(held!!)
                }
            }
            val joining = async { runCatching { client.join() } }
            runCurrent(); advanceTimeBy(601); runCurrent()
            assertTrue(joining.await().isFailure, mode)
            assertFalse(relay.published.any { it.kind == KIND_ROSTER }, mode)
            assertFailsWith<IllegalStateException> { client.sendChat("blocked") }
            desk.cancelAndJoin()
        }
    }

    @Test fun `leave cancels admission and a late root grant cannot resume it`() = runTest {
        val room = Fixtures.room(); val relay = FakeRelay()
        val client = session(room, Fixtures.primary(room, 1, 2), relay, authority = authority,
            epochGate = { _, _ -> EpochGateResult.COMMITTED }, requireFreshEpoch = true)
        val joining = async { runCatching { client.join() } }; runCurrent()
        val request = relay.published.single()
        client.leave(); runCurrent()
        assertTrue(joining.await().isFailure)
        relay.publish(encodeEpochGrant(room.roomId, root, request.pubkey, request.id, 0, RoomEpoch(0, secret)))
        advanceTimeBy(30_000); runCurrent()
        assertFalse(relay.published.any { it.kind == KIND_ROSTER })
        assertFailsWith<IllegalStateException> { client.sendChat("blocked") }
    }

    @Test fun `an equal epoch number with a different root key is not admission confirmation`() = runTest {
        val room = Fixtures.room(); val relay = FakeRelay()
        val client = session(room, Fixtures.primary(room, 1, 2), relay, authority = authority,
            initialEpoch = deriveEpoch(RoomEpoch(1, ByteArray(32) { 8 })),
            epochGate = { _, _ -> EpochGateResult.COMMITTED }, requireFreshEpoch = true)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            relay.transport().subscribe(listOf(Filter(kinds = listOf(KIND_EPOCH_REQUEST)))).collect { request ->
                relay.publish(encodeEpochGrant(room.roomId, root, request.pubkey, request.id, currentTime / 1000,
                    RoomEpoch(1, ByteArray(32) { 9 })))
            }
        }
        val joining = async { runCatching { client.join() } }; runCurrent()
        assertTrue(joining.await().isFailure)
        assertFalse(relay.published.any { it.kind == KIND_ROSTER })
    }
}
