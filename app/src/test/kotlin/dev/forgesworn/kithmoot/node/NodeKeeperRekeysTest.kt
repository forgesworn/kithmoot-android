package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.epoch.EpochVault
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.*

class NodeKeeperRekeysTest {
    private val now = 1_900_000_000L
    private val root = LocalSigner(ByteArray(32) { 3 }); private val service = LocalSigner(ByteArray(32) { 5 })
    private val secret = ByteArray(32) { 6 }; private val room = deriveRoom(secret)
    private val node = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), room.roomId, "https://node.example", service.pubkey, now).toJson(), now))
    private val owner = PrimaryIdentity.create(room.roomId, now + 86400, now, participantSecretKey = ByteArray(32) { 2 }, deviceSecretKey = ByteArray(32) { 4 })
    private val guest = PrimaryIdentity.create(room.roomId, now + 86400, now, participantSecretKey = ByteArray(32) { 7 }, deviceSecretKey = ByteArray(32) { 8 })
    private val store = NodeStore(NodeMemoryStorage(), NodeMemoryStorage(), NodeMemoryStorage())
    private val vault = EpochVault(NodeMemoryStorage(), NodeMemoryStorage())
    private val posts = mutableListOf<GroupEvent>()
    private var fail = false
    private fun keeper() = NodeKeeperAdmissions(node, owner.participant, store, { event, _ -> posts += event; if (fail) throw NodeRequestException() }, { true }, { now })
    private suspend fun prepare() {
        store.save(NodeCustody.read(buildJsonObject {
            put("v", 1); put("node", node.wire); put("participant", owner.participant); put("deviceKey", owner.deviceSecretKey.toHex())
            put("credential", owner.credential.toJson()); put("secret", secret.toHex()); put("rootKey", root.secretKeyForStorage().toHex()); put("phase", "creating")
        }))
        vault.initialise(node.room, root.pubkey, secret, now)
        keeper().admit(owner.credential.toJson(), true); keeper().admit(guest.credential.toJson(), true)
        store.update(owner.participant, node.room) { it.changed { put("pendingRemoval", guest.participant) } }
        posts.clear()
    }
    @Test fun `restart after a lost revocation ACK keeps removal intent and signs only after every ACK`(): Unit = runBlocking {
        prepare(); fail = true
        val initial = requireNotNull(store.custody(owner.participant, node.room))
        assertFailsWith<NodeRequestException> { NodeKeeperRekeys.prepareRemoval(initial, store, keeper(), vault, { true }, { now }) }
        val interrupted = requireNotNull(store.custody(owner.participant, node.room))
        assertEquals(guest.participant, interrupted.pendingRemoval); assertNull(interrupted.pendingRekey)
        assertFailsWith<IllegalArgumentException> { keeper().admit(owner.credential.toJson()) }
        val revocation = posts.single()
        fail = false
        NodeKeeperRekeys.prepareRemoval(interrupted, store, keeper(), vault, { true }, { now })
        assertEquals(revocation, posts.last())
        val staged = requireNotNull(store.custody(owner.participant, node.room)); val event = requireNotNull(staged.pendingRekey)
        val current = EpochKeys(0, room.roomId, room.roomKey)
        val ownerNotice = requireNotNull(decodeRekeyEvent(event, node.room, root.pubkey, current, owner.deviceSecretKey))
        val guestNotice = requireNotNull(decodeRekeyEvent(event, node.room, root.pubkey, current, guest.deviceSecretKey))
        assertNotNull(ownerNotice.secret); assertNull(guestNotice.secret); assertEquals(listOf(guest.participant), guestNotice.removed)
        NodeKeeperRekeys.prepareRemoval(staged, store, keeper(), vault, { true }, { now + 1 })
        assertEquals(event, store.custody(owner.participant, node.room)?.pendingRekey)
        assertEquals(0, vault.get(node.room)?.currentEpoch)
    }
    @Test fun `pending exact source rekey restores the durable epoch and cannot advance twice`(): Unit = runBlocking {
        prepare()
        NodeKeeperRekeys.prepareRemoval(requireNotNull(store.custody(owner.participant, node.room)), store, keeper(), vault, { true }, { now })
        val saved = requireNotNull(store.custody(owner.participant, node.room)); val event = requireNotNull(saved.pendingRekey)
        NodeKeeperRekeys.restorePending(saved, vault, now + 1)
        val durable = requireNotNull(vault.get(node.room))
        assertEquals(1, durable.currentEpoch); assertEquals(event.id, durable.activationCause)
        assertEquals(event.id, vault.rekeyAt(node.room, 1)?.id)
        assertEquals(listOf(guest.participant), durable.removed)
        NodeKeeperRekeys.restorePending(saved, vault, now + 2)
        assertEquals(1, vault.get(node.room)?.currentEpoch)
        assertNotNull(store.custody(owner.participant, node.room)?.pendingRekey)
    }
}
