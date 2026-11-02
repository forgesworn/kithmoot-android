package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.serialization.json.*
import kotlin.test.*

internal class NodeMemoryStorage : RoomStorage {
    var bytes: ByteArray? = null
    var refuse = false
    override fun read() = bytes?.copyOf()
    override fun write(value: ByteArray) { check(!refuse); bytes = value.copyOf() }
    override fun reset() { bytes = null }
}
class NodeStoreTest {
    private val now = 1_900_000_000L
    private val owner = LocalSigner(ByteArray(32) { 2 })
    private val root = LocalSigner(ByteArray(32) { 3 })
    private val device = LocalSigner(ByteArray(32) { 4 })
    private val service = LocalSigner(ByteArray(32) { 5 })
    private val secret = ByteArray(32) { 6 }
    private val room = deriveRoom(secret).roomId
    private val descriptor = NodeRooms.sign(root.secretKeyForStorage(), room, "https://node.example", service.pubkey, now).toJson()
    private val credential = Events.sign(owner.secretKeyForStorage(), KIND_DEVICE_CREDENTIAL, now,
        listOf(listOf("d", room), listOf("device", device.pubkey), listOf("expiration", (now + 3600).toString())), "")
    private val grant = buildGrant(root.secretKeyForStorage(), room, device.pubkey, "node.example", listOf("turn", "relay-read", "relay-write"), null, "ab".repeat(16), now, now + 3600)
    private val pairs = NodeMemoryStorage(); private val rooms = NodeMemoryStorage(); private val ops = NodeMemoryStorage()
    private fun store() = NodeStore(pairs, rooms, ops)
    private fun custody(phase: String = "creating") = NodeCustody.read(buildJsonObject {
        put("v", 1); put("node", descriptor); put("participant", owner.pubkey); put("deviceKey", device.secretKeyForStorage().toHex()); put("secret", secret.toHex())
        put("credential", credential.toJson()); put("rootKey", root.secretKeyForStorage().toHex()); put("phase", phase)
        if (phase == "admitted") put("grant", grant.toJson())
    })
    @Test fun `restart preserves the creating authority before any enrolment and never replaces its device`() {
        store().save(custody()); val restored = requireNotNull(store().custody(owner.pubkey, room))
        assertEquals("creating", restored.phase); assertContentEquals(root.secretKeyForStorage(), restored.rootKey)
        assertContentEquals(secret, restored.roomSecret); assertEquals(device.pubkey, restored.device)
        assertFalse(restored.toString().contains(secret.toHex()))
        val foreign = LocalSigner(ByteArray(32) { 7 })
        val credential = Events.sign(owner.secretKeyForStorage(), KIND_DEVICE_CREDENTIAL, now,
            listOf(listOf("d", room), listOf("device", foreign.pubkey), listOf("expiration", (now + 3600).toString())), "")
        val changed = restored.changed { put("deviceKey", foreign.secretKeyForStorage().toHex()); put("credential", credential.toJson()) }
        assertFailsWith<IllegalArgumentException> { store().save(changed) }
        assertEquals(device.pubkey, requireNotNull(store().custody(owner.pubkey, room)).device)
    }
    @Test fun `local unpair and room wipe leave only public operation references for the owner`() {
        val pair = NodePair("https://node.example", service.pubkey, owner.pubkey)
        val operation = NodeOperationRef(pair, "cd".repeat(16))
        store().savePair(pair); store().save(custody("admitted")); store().rememberOperation(operation)
        assertNull(store().pair(device.pubkey)); assertNull(store().custody(device.pubkey, room)); assertTrue(store().operations(device.pubkey).isEmpty())
        store().unpair(owner.pubkey); assertNotNull(store().custody(owner.pubkey, room))
        store().forgetRoom(room); assertNull(store().custody(owner.pubkey, room)); assertEquals(listOf(operation), store().operations(owner.pubkey))
        val public = requireNotNull(ops.bytes).toString(Charsets.UTF_8)
        for (private in listOf(room, secret.toHex(), root.secretKeyForStorage().toHex(), device.secretKeyForStorage().toHex(), descriptor.getValue("id").jsonPrimitive.content)) assertFalse(public.contains(private))
    }
    @Test fun `failed commits and corrupted ciphertext data do not mint empty state or new identities`() {
        store().save(custody()); val before = rooms.bytes!!.copyOf(); rooms.refuse = true
        assertFailsWith<IllegalStateException> { store().save(custody("admitted")) }; assertContentEquals(before, rooms.bytes)
        rooms.refuse = false; rooms.bytes = "broken".toByteArray()
        assertFailsWith<Exception> { store().custody(owner.pubkey, room) }; assertContentEquals("broken".toByteArray(), rooms.bytes)
    }
    @Test fun `terminal revocation receiver survives restart and cannot be discarded as malformed state`() {
        val receiver = GrantReceiver(root.pubkey, room, device.pubkey)
        assertTrue(receiver.offer(grant.toJson()))
        val revoked = buildGrant(root.secretKeyForStorage(), room, device.pubkey, "node.example", listOf("turn", "relay-read", "relay-write"), null, "ab".repeat(16), now + 1, now + 3600, "revoked")
        assertTrue(receiver.offer(revoked.toJson()))
        store().save(custody("admitted").changed { put("receiver", receiver.snapshot()) })
        val saved = requireNotNull(store().custody(owner.pubkey, room))
        assertTrue(GrantReceiver.restore(root.pubkey, room, device.pubkey, saved.receiver).lockedOut("node.example", now + 2))
        assertFailsWith<IllegalArgumentException> { saved.changed { put("receiver", buildJsonObject { put("events", JsonArray(listOf(JsonObject(grant.toJson() + ("sig" to JsonPrimitive("00".repeat(64))))))); put("acknowledged", JsonArray(emptyList())) }) } }
    }
    @Test fun `guest refresh cannot discard an existing authority key`() {
        val keeper = custody("admitted"); store().save(keeper)
        val guest = NodeCustody.read(JsonObject(keeper.wire - "rootKey"))
        assertFailsWith<IllegalArgumentException> { store().save(guest) }
        assertContentEquals(root.secretKeyForStorage(), store().custody(owner.pubkey, room)!!.rootKey)
    }
    @Test fun `lost closure ACK retains an immutable request and admitted state cannot regress`() {
        val pending = buildJsonObject { put("room", room); put("operation", "cd".repeat(16)); put("deadline", now + 90) }
        store().save(custody("admitted").changed { put("pendingClose", pending) })
        store().save(requireNotNull(store().custody(owner.pubkey, room)))
        assertFailsWith<IllegalArgumentException> { store().save(custody("admitted").changed { put("pendingClose", JsonObject(pending + ("deadline" to JsonPrimitive(now + 91)))) }) }
        assertFailsWith<IllegalArgumentException> { store().save(custody()) }
    }
}
