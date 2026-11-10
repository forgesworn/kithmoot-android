package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.AccountRoomOwner
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class NodeSharedOwnersTest {
    private val now = 1_900_000_000L
    private val root = LocalSigner(ByteArray(32) { 3 }); private val service = LocalSigner(ByteArray(32) { 5 })
    private val secret = ByteArray(32) { 6 }; private val room = deriveRoom(secret)
    private val node = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), room.roomId, "https://node.example", service.pubkey, now).toJson(), now))
    private val person = PrimaryIdentity.create(room.roomId, now + 86400, now, participantSecretKey = ByteArray(32) { 7 }, deviceSecretKey = ByteArray(32) { 8 })
    private val grant = buildGrant(root.secretKeyForStorage(), room.roomId, person.devicePubkey, "node.example", listOf("turn", "relay-read", "relay-write"), null, "b".repeat(32), now, now + NODE_HORIZON_SECONDS)
    private val store = NodeStore(NodeMemoryStorage(), NodeMemoryStorage(), NodeMemoryStorage())
    private val memberSigner = LocalSigner(ByteArray(32) { 10 })
    private val member = memberSigner.pubkey
    private val gone = LocalSigner(ByteArray(32) { 11 }).pubkey
    /** This phone as a member of the moved room, admitted without its owner key. */
    private fun admitted() = store.save(NodeCustody.read(buildJsonObject {
        put("v", 1); put("node", node.wire); put("participant", person.participant); put("deviceKey", person.deviceSecretKey.toHex())
        put("credential", person.credential.toJson()); put("secret", secret.toHex()); put("phase", "admitted"); put("grant", grant.toJson())
    }))
    private fun owner(key: ByteArray = root.secretKeyForStorage(), members: List<String> = listOf(member, gone), removed: List<String> = listOf(gone)) =
        requireNotNull(AccountRoomOwner.of(key.toHex(), members, removed))

    @Test fun `a member phone keeps a moved room once another of the account's devices shares its owner key`() {
        admitted()
        assertNull(NodeSharedOwners.export(store, person.participant, room.roomId))
        assertTrue(NodeSharedOwners.adopt(store, person.participant, room.roomId, owner()))
        val kept = requireNotNull(store.custody(person.participant, room.roomId))
        assertEquals(root.pubkey, Schnorr.publicKeyHex(requireNotNull(kept.rootKey)))
        val state = NodeKeeperState.read(kept.keeper, node)
        assertEquals(listOf(member, person.participant).sorted(), state.members); assertEquals(listOf(gone), state.removed)
        assertEquals(root.secretKeyForStorage().toHex(), NodeSharedOwners.export(store, person.participant, room.roomId)?.key)
        // Kept once: a second copy changes nothing.
        assertFalse(NodeSharedOwners.adopt(store, person.participant, room.roomId, owner()))
    }

    private fun credential(signer: LocalSigner, seed: Int) = Events.sign(signer.secretKeyForStorage(), KIND_DEVICE_CREDENTIAL, now,
        listOf(listOf("d", room.roomId), listOf("device", LocalSigner(ByteArray(32) { seed.toByte() }).pubkey), listOf("expiration", (now + 90 * 86400).toString())), "").toJson()

    @Test fun `the phone lets in a member the shared list names, without asking, and nobody removed`(): Unit = runBlocking {
        admitted(); assertTrue(NodeSharedOwners.adopt(store, person.participant, room.roomId, owner()))
        val posted = mutableListOf<GroupEvent>()
        val keeper = NodeKeeperAdmissions(node, person.participant, store, { event, _ -> posted += event }, { true }, { now })
        assertNotNull(keeper.admit(credential(memberSigner, 20)))
        assertEquals(1, posted.size)
        assertNull(keeper.admit(credential(LocalSigner(ByteArray(32) { 11 }), 21), manuallyApproved = true))
    }

    @Test fun `does nothing while the account's network policy forbids it`(): Unit = runBlocking {
        admitted(); assertTrue(NodeSharedOwners.adopt(store, person.participant, room.roomId, owner(removed = emptyList())))
        NodeSharedOwners.apply(store, person.participant, room.roomId, owner(members = emptyList(), removed = listOf(member)), { false }) { _, _ -> fail("posted while forbidden") }
        assertTrue(member in NodeKeeperState.read(requireNotNull(store.custody(person.participant, room.roomId)).keeper, node).members)
    }

    @Test fun `never takes a key that is not the moved room's owner`() {
        admitted()
        assertFalse(NodeSharedOwners.adopt(store, person.participant, room.roomId, owner(ByteArray(32) { 9 })))
        assertNull(store.custody(person.participant, room.roomId)?.rootKey)
    }

    @Test fun `a removal another device shared ends membership here and keeps the person out`(): Unit = runBlocking {
        admitted(); assertTrue(NodeSharedOwners.adopt(store, person.participant, room.roomId, owner(removed = emptyList())))
        val posted = mutableListOf<GroupEvent>()
        NodeSharedOwners.apply(store, person.participant, room.roomId, owner(members = emptyList(), removed = listOf(member, gone)), { true }) { event, _ -> posted += event }
        val state = NodeKeeperState.read(requireNotNull(store.custody(person.participant, room.roomId)).keeper, node)
        assertEquals(listOf(person.participant), state.members); assertEquals(listOf(member, gone).sorted(), state.removed)
        assertTrue(posted.isEmpty())
    }
}
