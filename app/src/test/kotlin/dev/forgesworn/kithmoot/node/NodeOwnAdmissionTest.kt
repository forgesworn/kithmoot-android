package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.AccountRoomOwner
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class NodeOwnAdmissionTest {
    private val now = 1_900_000_000L
    private val root = LocalSigner(ByteArray(32) { 3 }); private val service = LocalSigner(ByteArray(32) { 5 })
    private val secret = ByteArray(32) { 6 }; private val room = deriveRoom(secret)
    private fun record(existing: Boolean = false) = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), room.roomId, "https://node.example", service.pubkey, now,
        endsAt = if (existing) null else now + 7200, existing = if (existing) NodeExistingRoomPolicy(false) else null).toJson(), now))
    private val person = PrimaryIdentity.create(room.roomId, now + 86400, now, participantSecretKey = ByteArray(32) { 7 }, deviceSecretKey = ByteArray(32) { 8 })
    private val member = LocalSigner(ByteArray(32) { 10 }).pubkey
    private fun owner(key: ByteArray = root.secretKeyForStorage(), removed: List<String> = emptyList()) =
        requireNotNull(AccountRoomOwner.of(key.toHex(), listOf(member), removed))
    private val posted = mutableListOf<GroupEvent>()
    private val post: suspend (GroupEvent, ParticipantSigner) -> Unit = { event, authority -> assertEquals(root.pubkey, authority.pubkey); posted += event }

    @Test fun `a device of the account that made a node room lets itself in with the shared owner key and secret`() = runBlocking {
        val store = NodeStore(NodeMemoryStorage(), NodeMemoryStorage(), NodeMemoryStorage())
        val held = admitOwnNodeDevice(record(), person, owner(), secret.toHex(), store, post, { true }, { now })
        assertEquals("admitted", held.phase); assertEquals(person.devicePubkey, held.device)
        assertEquals(root.pubkey, Schnorr.publicKeyHex(requireNotNull(held.rootKey)))
        assertContentEquals(secret, held.roomSecret)
        assertEquals(1, posted.size)
        val grant = assertIs<ParsedGrant>((parseGroupEvent(held.grant, keepers = listOf(root.pubkey), servers = listOf("node.example")) as GroupEventVerdict.Ok).parsed)
        assertEquals(person.devicePubkey, grant.device)
        // The members the account shared are known here, so this phone lets them in too.
        assertEquals(listOf(member, person.participant).sorted(), NodeKeeperState.read(held.keeper, held.node).members)
    }

    @Test fun `a removal, a moved room, or a key or secret that is not the room's is left to the keeper and saves nothing`() = runBlocking {
        val attempts: List<suspend (NodeStore) -> NodeCustody> = listOf(
            { s -> admitOwnNodeDevice(record(), person, owner(removed = listOf(LocalSigner(ByteArray(32) { 11 }).pubkey)), secret.toHex(), s, post, { true }, { now }) },
            { s -> admitOwnNodeDevice(record(), person, owner(key = ByteArray(32) { 12 }), secret.toHex(), s, post, { true }, { now }) },
            { s -> admitOwnNodeDevice(record(), person, owner(), ByteArray(32) { 13 }.toHex(), s, post, { true }, { now }) },
            { s -> admitOwnNodeDevice(record(existing = true), person, owner(), secret.toHex(), s, post, { true }, { now }) },
        )
        for (attempt in attempts) {
            val store = NodeStore(NodeMemoryStorage(), NodeMemoryStorage(), NodeMemoryStorage())
            assertFails { attempt(store) }
            assertNull(store.custody(person.participant, room.roomId))
        }
        assertTrue(posted.isEmpty())
    }

    @Test fun `a grant the node does not take leaves no custody, so the device can still ask`() = runBlocking {
        val store = NodeStore(NodeMemoryStorage(), NodeMemoryStorage(), NodeMemoryStorage())
        assertFails { admitOwnNodeDevice(record(), person, owner(), secret.toHex(), store, { _, _ -> throw NodeRequestException(503) }, { true }, { now }) }
        assertNull(store.custody(person.participant, room.roomId))
    }
}
