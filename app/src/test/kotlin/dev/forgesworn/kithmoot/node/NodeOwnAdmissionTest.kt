package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.AccountRoomOwner
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class NodeOwnAdmissionTest {
    private val now = 1_900_000_000L
    private val root = LocalSigner(ByteArray(32) { 3 }); private val service = LocalSigner(ByteArray(32) { 5 })
    private val secret = ByteArray(32) { 6 }; private val room = deriveRoom(secret)
    private fun record(existing: Boolean = false) = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), room.roomId, "https://node.example", service.pubkey, now,
        endsAt = if (existing) null else now + 7200, existing = if (existing) NodeExistingRoomPolicy(false) else null).toJson(), now))
    private val person = PrimaryIdentity.create(room.roomId, now + 86400, now, participantSecretKey = ByteArray(32) { 7 }, deviceSecretKey = ByteArray(32) { 8 })
    private val member = LocalSigner(ByteArray(32) { 10 }).pubkey
    private fun owner(key: ByteArray = root.secretKeyForStorage(), removed: List<String> = emptyList(), members: List<String> = listOf(member)) =
        requireNotNull(AccountRoomOwner.of(key.toHex(), members, removed))
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

    private val account = LocalSigner(ByteArray(32) { 7 })
    /** As the node's ledger does: the identical event again changes nothing, the same grant id
     * must be newer, and a different active grant id for a device it holds is refused. */
    private class Ledger(val now: () -> Long) {
        val held = mutableMapOf<String, ParsedGrant>(); val sent = mutableListOf<GroupEvent>(); var lose = 0
        val post: suspend (GroupEvent, ParticipantSigner) -> Unit = { event, _ ->
            sent += event
            val grant = assertIs<ParsedGrant>((parseGroupEvent(event.toJson(), keepers = listOf(LocalSigner(ByteArray(32) { 3 }).pubkey), servers = listOf("node.example")) as GroupEventVerdict.Ok).parsed)
            val current = held[grant.device]
            if (current?.event?.id != event.id) {
                if (current != null && current.grantId == grant.grantId && grant.createdAt <= current.createdAt) throw NodeRequestException(409, "stale-grant")
                if (current != null && current.grantId != grant.grantId && current.status == "active" && current.expiration > now()) throw NodeRequestException(409, NodeRequestException.DEVICE_ALREADY_GRANTED)
                held[grant.device] = grant
            }
            if (lose > 0) { lose--; throw java.io.IOException("The reply was lost") }
        }
    }

    @Test fun `a lost reply to a self-admission the node took is met by sending the same grant again`() = runBlocking {
        val node = record(); val identities = NodeJoinIdentityStore(NodeMemoryStorage()); val store = NodeStore(NodeMemoryStorage(), NodeMemoryStorage(), NodeMemoryStorage())
        val who = identities.identity(node, account, { now })
        val ledger = Ledger { now }; ledger.lose = 1
        assertFailsWith<java.io.IOException> { admitOwnNodeDevice(node, who, owner(), secret.toHex(), store, ledger.post, { true }, { now }, identities) }
        assertNull(store.custody(who.participant, room.roomId))
        // In the same second, as the retry straight after a lost reply runs.
        val held = admitOwnNodeDevice(node, identities.identity(node, account, { now }), owner(), secret.toHex(), store, ledger.post, { true }, { now }, identities)
        assertEquals("admitted", held.phase); assertEquals(who.devicePubkey, held.device)
        assertEquals(2, ledger.sent.size); assertEquals(ledger.sent[0].id, ledger.sent[1].id)
        assertEquals(ledger.sent[0].toJson(), held.grant)
        assertNull(identities.rememberedGrant(node, account.pubkey))
    }

    @Test fun `without its kept grant the same retry is refused, as it was before`() = runBlocking {
        val node = record(); val identities = NodeJoinIdentityStore(NodeMemoryStorage()); val store = NodeStore(NodeMemoryStorage(), NodeMemoryStorage(), NodeMemoryStorage())
        val who = identities.identity(node, account, { now })
        val ledger = Ledger { now }; ledger.lose = 1
        assertFails { admitOwnNodeDevice(node, who, owner(), secret.toHex(), store, ledger.post, { true }, { now }) }
        val refused = assertFailsWith<NodeRequestException> { admitOwnNodeDevice(node, who, owner(), secret.toHex(), store, ledger.post, { true }, { now + 5 }) }
        assertTrue(refused.alreadyGranted)
    }

    @Test fun `a grant that cannot be kept is never sent`() = runBlocking {
        val node = record(); val memory = NodeMemoryStorage(); val identities = NodeJoinIdentityStore(memory); val store = NodeStore(NodeMemoryStorage(), NodeMemoryStorage(), NodeMemoryStorage())
        val who = identities.identity(node, account, { now })
        val ledger = Ledger { now }; memory.refuse = true
        assertFails { admitOwnNodeDevice(node, who, owner(), secret.toHex(), store, ledger.post, { true }, { now }, identities) }
        assertTrue(ledger.sent.isEmpty()); assertNull(store.custody(who.participant, room.roomId))
    }

    @Test fun `a kept grant this device cannot use is ignored and a fresh one admits it`() = runBlocking {
        val node = record()
        val unusable = listOf(
            buildGrant(root.secretKeyForStorage(), room.roomId, LocalSigner(ByteArray(32) { 14 }).pubkey, "node.example", listOf("turn", "relay-read", "relay-write"), null, "ab".repeat(16), now, now + 3600),
            null, "revoked", "foreign")
        for (kind in unusable) {
            val memory = NodeMemoryStorage(); val identities = NodeJoinIdentityStore(memory); val store = NodeStore(NodeMemoryStorage(), NodeMemoryStorage(), NodeMemoryStorage())
            val who = identities.identity(node, account, { now })
            val grant = when (kind) {
                is GroupEvent -> kind
                "revoked" -> buildGrant(root.secretKeyForStorage(), room.roomId, who.devicePubkey, "node.example", listOf("turn", "relay-read", "relay-write"), null, "cd".repeat(16), now, now + 3600, "revoked")
                "foreign" -> buildGrant(ByteArray(32) { 15 }, room.roomId, who.devicePubkey, "node.example", listOf("turn", "relay-read", "relay-write"), null, "ef".repeat(16), now, now + 3600)
                else -> null
            }
            if (grant != null) {
                assertFails { identities.rememberGrant(node, account.pubkey, grant) }
                // Written behind the store's back, it still never stops the device asking.
                val rows = Json.parseToJsonElement(memory.bytes!!.toString(Charsets.UTF_8)).jsonObject
                memory.bytes = JsonObject(rows.mapValues { JsonObject(it.value.jsonObject + ("grant" to grant.toJson())) }).toString().toByteArray()
                assertEquals(who.devicePubkey, identities.identity(node, account, { now }).devicePubkey)
                assertNull(identities.rememberedGrant(node, account.pubkey))
            }
            val ledger = Ledger { now }
            val held = admitOwnNodeDevice(node, who, owner(), secret.toHex(), store, ledger.post, { true }, { now }, identities)
            assertEquals("admitted", held.phase); assertEquals(1, ledger.sent.size)
        }
    }

    @Test fun `a kept grant ahead of the clock is never replaced, and is sent once the clock reaches it`() = runBlocking {
        val node = record(); val identities = NodeJoinIdentityStore(NodeMemoryStorage()); val store = NodeStore(NodeMemoryStorage(), NodeMemoryStorage(), NodeMemoryStorage())
        val who = identities.identity(node, account, { now })
        // As a phone would have kept it before its clock stepped back; the node may hold it.
        val ahead = buildGrant(root.secretKeyForStorage(), room.roomId, who.devicePubkey, "node.example", listOf("turn", "relay-read", "relay-write"), null, "ab".repeat(16), now + 600, now + 3600)
        identities.rememberGrant(node, account.pubkey, ahead)
        val ledger = Ledger { now }
        assertFailsWith<IllegalStateException> { admitOwnNodeDevice(node, who, owner(), secret.toHex(), store, ledger.post, { true }, { now }, identities) }
        assertTrue(ledger.sent.isEmpty()); assertNull(store.custody(who.participant, room.roomId))
        assertEquals(ahead.toJson(), identities.rememberedGrant(node, account.pubkey))
        val later = now + 600 - GRANT_FUTURE_SKEW_SECONDS
        val held = admitOwnNodeDevice(node, identities.identity(node, account, { later }), owner(), secret.toHex(), store, ledger.post, { true }, { later }, identities)
        assertEquals("admitted", held.phase); assertEquals(listOf(ahead.id), ledger.sent.map { it.id })
    }

    @Test fun `however many members the account shared, a lost reply is still met by the same grant`() = runBlocking {
        val node = record(); val identities = NodeJoinIdentityStore(NodeMemoryStorage()); val store = NodeStore(NodeMemoryStorage(), NodeMemoryStorage(), NodeMemoryStorage())
        // More than the keeper learns, every one sorting before this account.
        val many = (0 until 120).map { "00".repeat(31) + "%02x".format(it) }
        assertTrue(many.all { it < account.pubkey })
        val who = identities.identity(node, account, { now })
        val ledger = Ledger { now }; ledger.lose = 1
        assertFailsWith<java.io.IOException> { admitOwnNodeDevice(node, who, owner(members = many), secret.toHex(), store, ledger.post, { true }, { now }, identities) }
        val held = admitOwnNodeDevice(node, who, owner(members = many), secret.toHex(), store, ledger.post, { true }, { now }, identities)
        assertEquals("admitted", held.phase); assertEquals(ledger.sent[0].id, ledger.sent[1].id)
        val learned = NodeKeeperState.read(held.keeper, held.node).members
        assertTrue(account.pubkey in learned); assertEquals(NodeKeeperAdmissions.MAX_LEARNED, learned.size)
    }
}
