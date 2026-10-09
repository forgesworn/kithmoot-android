package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.relay.HybridRoomTransport
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LiveRoomEntryTest {
    private val root = Fixtures.key(41)
    private val secret = ByteArray(32) { 7 }
    private val invitation = RoomInvitation(ByteArray(32) { 5 }, Schnorr.publicKeyHex(root), true)
    private val context = LivePersistentContext(invitation, deriveRoom(secret).roomId)
    private val descriptor = encodeLivePersistentDescriptor(context)
    private val welcome = encodePersistentInvitation(RoomInvitationHost(invitation, root), secret, 0)

    @Test fun `mixed room entry recovers a lost answer and chats once over both lanes`() = runTest {
        val mesh = FakeRelay(); val relay = FakeRelay(); val room = Fixtures.room()
        val keeperPath = HybridRoomTransport(mesh.transport(), relay.transport()) { currentTime / 1000 }
        val clientPath = HybridRoomTransport(mesh.transport(), relay.transport()) { currentTime / 1000 }
        val identity = Fixtures.primary(room, 1, 2)
        val requests = mutableListOf<NostrEvent>(); val answers = mutableMapOf<String, NostrEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            keeperPath.subscribe(listOf(Filter(kinds = listOf(KIND_INVITATION_REQUEST)))).collect { request ->
                requests += request
                val answer = answers.getOrPut(request.id) {
                    encodeLivePersistentAnswer(context, request, welcome, root, 0, currentTime / 1000)
                }
                // The first complete answer is lost before either selected lane.
                if (requests.size > 1) keeperPath.publish(answer)
            }
        }
        val peer = session(room, Fixtures.primary(room, 3, 4), relay, transport = keeperPath,
            authority = invitation.inviter, epochResponder = { request ->
                encodeEpochGrant(room.roomId, root, request.pubkey, request.id, currentTime / 1000, RoomEpoch(0, secret))
            })
        peer.join(); runCurrent()
        val joining = async {
            joinLivePersistentRoom(invitation, descriptor, identity.devicePubkey, clientPath,
                { currentTime / 1000 }, { currentTime }) { proof ->
                session(room, identity, relay, transport = clientPath, authority = invitation.inviter,
                    expectedEpoch = proof.epochHint.toInt(), epochGate = { _, _ -> EpochGateResult.COMMITTED }, requireFreshEpoch = true)
            }
        }
        runCurrent(); assertFalse(joining.isCompleted); assertEquals(1, requests.size)
        assertFalse(mesh.published.any { it.kind == KIND_ROSTER && it.pubkey == identity.devicePubkey })
        advanceTimeBy(10_000); runCurrent()
        val client = joining.await()
        assertEquals(2, requests.size); assertEquals(1, requests.map { it.id }.distinct().size)
        assertEquals(1, answers.size)
        client.sendChat("mixed client"); peer.sendChat("mixed reply"); runCurrent()
        assertEquals(1, peer.chat.value.count { it.body == "mixed client" })
        assertEquals(1, client.chat.value.count { it.body == "mixed reply" })
        assertEquals(mesh.published.filter { it.kind == KIND_CHAT }.map { it.id }, relay.published.filter { it.kind == KIND_CHAT }.map { it.id })
        client.leave(); peer.leave()
    }

    @Test fun `verified proof and fresh root grant enter an actual room before both way chat`() = runTest {
        val relay = FakeRelay(); val room = Fixtures.room(); val identity = Fixtures.primary(room, 1, 2)
        val peer = session(room, Fixtures.primary(room, 3, 4), relay); peer.join()
        val base = relay.transport()
        val transport = object : RoomTransport by base {
            override fun publish(event: NostrEvent) {
                base.publish(event)
                if (event.kind == KIND_INVITATION_REQUEST) relay.publish(encodeLivePersistentAnswer(context, event, welcome, root, 0, currentTime / 1000))
            }
            override fun publishRecovery(event: NostrEvent) {
                base.publishRecovery(event)
                if (event.kind == KIND_EPOCH_REQUEST) relay.publish(encodeEpochGrant(room.roomId, root, event.pubkey, event.id, currentTime / 1000, RoomEpoch(0, secret)))
            }
        }
        val joining = async {
            joinLivePersistentRoom(invitation, descriptor, identity.devicePubkey, transport, { currentTime / 1000 }, { currentTime }) { proof ->
                assertEquals(context.roomId, deriveRoom(proof.admission.secret).roomId)
                session(room, identity, relay, transport = transport, authority = invitation.inviter,
                    expectedEpoch = proof.epochHint.toInt(), epochGate = { _, _ -> EpochGateResult.COMMITTED }, requireFreshEpoch = true)
            }
        }
        runCurrent(); val client = joining.await()
        client.sendChat("from admitted client"); peer.sendChat("reply to admitted client"); runCurrent()
        assertTrue(peer.chat.value.any { it.body == "from admitted client" })
        assertTrue(client.chat.value.any { it.body == "reply to admitted client" })
        assertEquals(listOf(KIND_INVITATION_REQUEST, KIND_EPOCH_REQUEST), relay.published.filter {
            it.kind == KIND_INVITATION_REQUEST || (it.kind == KIND_EPOCH_REQUEST && it.pubkey == identity.devicePubkey)
        }.map { it.kind })
    }

    @Test fun `retirement between proof and root confirmation cancels the created session`() = runTest {
        val relay = FakeRelay(); val room = Fixtures.room(); val identity = Fixtures.primary(room, 1, 2)
        var created: RoomSession? = null
        val base = relay.transport()
        val transport = object : RoomTransport by base {
            override fun publish(event: NostrEvent) {
                base.publish(event)
                if (event.kind == KIND_INVITATION_REQUEST) relay.publish(encodeLivePersistentAnswer(context, event, welcome, root, 0, 0))
            }
            override fun publishRecovery(event: NostrEvent) {
                base.publishRecovery(event)
                relay.publish(encodeInvitationRetirement(invitation, root, 0))
            }
        }
        val joining = async { runCatching {
            joinLivePersistentRoom(invitation, descriptor, identity.devicePubkey, transport, { currentTime / 1000 }, { currentTime }) { proof ->
                session(room, identity, relay, transport = transport, authority = invitation.inviter,
                    expectedEpoch = proof.epochHint.toInt(), epochGate = { _, _ -> EpochGateResult.COMMITTED }, requireFreshEpoch = true).also { created = it }
            }
        } }
        runCurrent(); assertTrue(joining.await().isFailure)
        assertNotNull(created)
        assertFailsWith<IllegalStateException> { created!!.sendChat("must stay closed") }
        advanceTimeBy(100_000); runCurrent()
        assertFalse(relay.published.any { it.kind == KIND_ROSTER })
    }

    @Test fun `app entry cannot complete without confirming its session`() = runTest {
        val relay = FakeRelay(); val identity = Fixtures.primary(Fixtures.room(), 1, 2)
        val base = relay.transport()
        val transport = object : RoomTransport by base {
            override fun publish(event: NostrEvent) {
                base.publish(event)
                if (event.kind == KIND_INVITATION_REQUEST) relay.publish(encodeLivePersistentAnswer(context, event, welcome, root, 0, 0))
            }
        }
        val result = async { runCatching {
            withLivePersistentRoomAdmission(invitation, descriptor, identity.devicePubkey, transport,
                { currentTime / 1000 }, { currentTime }) { _, _ -> "must not return" }
        } }
        runCurrent()
        assertTrue(result.await().isFailure)
        assertTrue(relay.published.none { it.kind == KIND_ROSTER })
    }

    @Test fun `invalid descriptor never publishes and a factory cannot substitute an ungated session`() = runTest {
        val relay = FakeRelay(); val room = Fixtures.room(); val identity = Fixtures.primary(room, 1, 2)
        assertFailsWith<IllegalArgumentException> {
            joinLivePersistentRoom(invitation, "invalid", identity.devicePubkey, relay.transport()) { error("must not create") }
        }
        assertTrue(relay.published.isEmpty())
        val base = relay.transport()
        val transport = object : RoomTransport by base {
            override fun publish(event: NostrEvent) {
                base.publish(event)
                relay.publish(encodeLivePersistentAnswer(context, event, welcome, root, 0, 0))
            }
        }
        val joining = async { runCatching {
            joinLivePersistentRoom(invitation, descriptor, identity.devicePubkey, transport, { currentTime / 1000 }, { currentTime }) {
                session(room, identity, relay, transport = transport)
            }
        } }
        runCurrent(); assertTrue(joining.await().isFailure)
        assertTrue(relay.published.all { it.kind == KIND_INVITATION_REQUEST || it.kind == KIND_INVITATION_GRANT })
    }
}
