package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class NodeJoinIdentityStoreTest {
    private val now = 1_900_000_000L
    private val root = LocalSigner(ByteArray(32) { 3 }); private val service = LocalSigner(ByteArray(32) { 5 })
    private val node = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), "ee".repeat(32), "https://node.example", service.pubkey, now).toJson(), now))
    @Test fun `local guest restart reuses participant and device without a room secret`(): Unit = runBlocking {
        val memory = NodeMemoryStorage()
        val first = NodeJoinIdentityStore(memory).identity(node, now = { now })
        val resumed = NodeJoinIdentityStore(memory).identity(node, now = { now + 1 })
        assertEquals(first.participant, resumed.participant); assertEquals(first.devicePubkey, resumed.devicePubkey)
        assertEquals(first.credential, resumed.credential)
        val row = Json.parseToJsonElement(memory.bytes!!.toString(Charsets.UTF_8)).jsonObject.values.single().jsonObject
        assertFalse(row.containsKey("secret")); assertFalse(row.containsKey("grant"))
    }
    @Test fun `failed signer keeps its original device and withdrawal releases no identity`(): Unit = runBlocking {
        val memory = NodeMemoryStorage(); val owner = LocalSigner(ByteArray(32) { 2 })
        val failed = object : ParticipantSigner by owner {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent = throw SignerException("fixture")
        }
        val store = NodeJoinIdentityStore(memory)
        assertFailsWith<SignerException> { store.identity(node, failed, { now }) }
        val before = Json.parseToJsonElement(memory.bytes!!.toString(Charsets.UTF_8)).jsonObject.values.single().jsonObject
        var allowed = true
        val withdrawn = object : ParticipantSigner by owner {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent { allowed = false; return owner.sign(kind, createdAt, tags, content) }
        }
        assertFailsWith<IllegalStateException> { store.identity(node, withdrawn, { now }, { allowed }) }
        val identity = store.identity(node, owner, { now })
        val after = Json.parseToJsonElement(memory.bytes!!.toString(Charsets.UTF_8)).jsonObject.values.single().jsonObject
        assertEquals(before["deviceKey"], after["deviceKey"]); assertEquals(owner.pubkey, identity.participant); assertFalse(after.containsKey("participantKey"))
    }
    @Test fun `corrupt saved credentials and changed node never mint replacements`(): Unit = runBlocking {
        val memory = NodeMemoryStorage(); val store = NodeJoinIdentityStore(memory)
        store.identity(node, now = { now })
        val before = memory.bytes!!.copyOf()
        val foreign = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), node.room, "https://other.example", service.pubkey, now).toJson(), now))
        assertFailsWith<IllegalArgumentException> { store.identity(foreign, now = { now }) }; assertContentEquals(before, memory.bytes)
        memory.bytes = "broken".toByteArray()
        assertFailsWith<Exception> { store.identity(node, now = { now }) }; assertContentEquals("broken".toByteArray(), memory.bytes)
    }
    @Test fun `a kept grant that has lapsed survives a credential refresh and never stops the device asking`(): Unit = runBlocking {
        val memory = NodeMemoryStorage(); val store = NodeJoinIdentityStore(memory); val owner = LocalSigner(ByteArray(32) { 2 })
        val first = store.identity(node, owner, { now })
        val grant = buildGrant(root.secretKeyForStorage(), node.room, first.devicePubkey, "node.example", listOf("turn", "relay-read", "relay-write"), null, "ab".repeat(16), now, now + 60)
        assertFailsWith<IllegalArgumentException> { store.identity(node, now = { now }).let { store.rememberGrant(node, it.participant, grant) } }
        store.rememberGrant(node, owner.pubkey, grant)
        val later = store.identity(node, owner, { now + 50_000 })
        assertEquals(first.devicePubkey, later.devicePubkey); assertNotEquals(first.credential, later.credential)
        assertEquals(grant.toJson(), store.rememberedGrant(node, owner.pubkey))
        store.forgetGrant(node, owner.pubkey); assertNull(store.rememberedGrant(node, owner.pubkey))
        assertEquals(later.credential, store.identity(node, owner, { now + 50_001 }).credential)
    }
    @Test fun `explicit room forget clears every local and account asking identity`(): Unit = runBlocking {
        val memory = NodeMemoryStorage(); val store = NodeJoinIdentityStore(memory)
        store.identity(node, now = { now }); store.identity(node, LocalSigner(ByteArray(32) { 2 }), { now })
        assertEquals(2, Json.parseToJsonElement(memory.bytes!!.toString(Charsets.UTF_8)).jsonObject.size)
        store.forgetRoom(node.room)
        assertTrue(Json.parseToJsonElement(memory.bytes!!.toString(Charsets.UTF_8)).jsonObject.isEmpty())
    }
}
