package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class NodeRoomCloserTest {
    private val now = 1_900_000_000L
    private var clock = now
    private val person = LocalSigner(ByteArray(32) { 2 }); private val root = LocalSigner(ByteArray(32) { 3 })
    private val device = LocalSigner(ByteArray(32) { 4 }); private val service = LocalSigner(ByteArray(32) { 5 })
    private val secret = ByteArray(32) { 6 }; private val room = deriveRoom(secret)
    private val node = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), room.roomId, "https://node.example", service.pubkey, now).toJson(), now))
    private val private = NodeMemoryStorage(); private val public = NodeMemoryStorage()
    private val store = NodeStore(NodeMemoryStorage(), private, public)
    private val sent = mutableListOf<JsonObject>(); private var fail = false; private var accepted = 0
    private fun prepare() {
        val credential = Events.sign(person.secretKeyForStorage(), KIND_DEVICE_CREDENTIAL, now, listOf(listOf("d", room.roomId), listOf("device", device.pubkey), listOf("expiration", (now + 3600).toString())), "")
        store.save(NodeCustody.read(buildJsonObject { put("v", 1); put("node", node.wire); put("participant", person.pubkey); put("deviceKey", device.secretKeyForStorage().toHex()); put("credential", credential.toJson()); put("secret", secret.toHex()); put("rootKey", root.secretKeyForStorage().toHex()); put("phase", "creating") }))
    }
    private fun closer() = NodeRoomCloser(node, person.pubkey, store, { _, body, signer ->
        assertEquals(root.pubkey, signer.pubkey); assertEquals(body, requireNotNull(store.custody(person.pubkey, room.roomId)).pendingClose)
        assertEquals(NodeEvidence.string(body, "operation"), store.operations(person.pubkey).single().operation)
        sent += body; if (fail) throw NodeRequestException(); NodeOperation("publishing")
    }, { accepted++ }, { true }, { clock })
    private fun epoch() = EpochKeys(0, room.roomId, room.roomKey)
    @Test fun `staging signs explicit closure encrypted retirement and closed destruct without changing traffic key`(): Unit = runBlocking {
        prepare(); val previous = room.roomKey.copyOf(); val body = closer().stage(::epoch)
        assertTrue(sent.isEmpty()); assertContentEquals(previous, room.roomKey)
        val operation = NodeEvidence.string(body, "operation")
        assertEquals(operation, requireNotNull(NodeRooms.readClosure(body["closure"], node, now)).operation)
        val events = body.getValue("events").jsonArray.map(NostrEvent::fromJson)
        assertEquals(operation to now, NodeRooms.readRetirement(events.single { it.kind == 1461 }.toJson(), node, secret, now))
        val evidence = requireNotNull(readRekeyEvidence(events.single { it.kind == 1462 }, room.roomId, root.pubkey, 0, room.roomKey))
        assertTrue(evidence.closed && evidence.destruct); assertEquals(1, evidence.epoch); assertTrue(evidence.members!!.isEmpty())
        assertEquals((now + NODE_HORIZON_SECONDS).toString(), events.single { it.kind == 1462 }.tags.single { it[0] == "expiration" }[1])
        assertEquals(body, requireNotNull(store.custody(person.pubkey, room.roomId)).pendingClose)
    }
    @Test fun `lost ACK retries identical ciphertext across restart then retains only public operation ref`(): Unit = runBlocking {
        prepare(); val body = closer().stage(::epoch); fail = true
        assertFailsWith<NodeRequestException> { closer().retry() }; assertEquals(0, accepted); assertNotNull(store.custody(person.pubkey, room.roomId))
        clock += 1; assertEquals(body, closer().stage { error("Must not re-sign on restart") })
        fail = false; assertEquals("publishing", closer().retry().phase)
        assertEquals(listOf(body, body), sent); assertEquals(1, accepted); assertNull(store.custody(person.pubkey, room.roomId))
        val kept = store.operations(person.pubkey).single(); assertEquals(NodeEvidence.string(body, "operation"), kept.operation)
        val raw = public.bytes!!.toString(Charsets.UTF_8)
        for (value in listOf(room.roomId, secret.toHex(), root.secretKeyForStorage().toHex(), device.secretKeyForStorage().toHex(), node.event.id)) assertFalse(raw.contains(value))
    }
    @Test fun `failed stage sends nothing and pending close blocks the grant desk`(): Unit = runBlocking {
        prepare(); private.refuse = true; assertFailsWith<IllegalStateException> { closer().stage(::epoch) }; assertTrue(sent.isEmpty())
        private.refuse = false; closer().stage(::epoch)
        val keeper = NodeKeeperAdmissions(node, person.pubkey, store, { _, _ -> error("No grant may post") }, { true }, { now })
        assertFailsWith<IllegalArgumentException> { keeper.renewDue() }; assertTrue(sent.isEmpty())
    }
}
