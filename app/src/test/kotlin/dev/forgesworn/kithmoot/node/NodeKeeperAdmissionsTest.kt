package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class NodeKeeperAdmissionsTest {
    private val now = 1_900_000_000L
    private var clock = now
    private val owner = LocalSigner(ByteArray(32) { 2 }); private val root = LocalSigner(ByteArray(32) { 3 })
    private val device = LocalSigner(ByteArray(32) { 4 }); private val service = LocalSigner(ByteArray(32) { 5 }); private val guest = LocalSigner(ByteArray(32) { 7 })
    private val secret = ByteArray(32) { 6 }; private val room = deriveRoom(secret).roomId
    private val store = NodeStore(NodeMemoryStorage(), NodeMemoryStorage(), NodeMemoryStorage())
    private val posted = mutableListOf<GroupEvent>()
    private var fail = false
    private lateinit var node: NodeRoomRecord
    private fun credential(person: LocalSigner, seed: Int) = Events.sign(person.secretKeyForStorage(), KIND_DEVICE_CREDENTIAL, clock,
        listOf(listOf("d", room), listOf("device", LocalSigner(ByteArray(32) { seed.toByte() }).pubkey), listOf("expiration", (clock + 90 * 86400).toString())), "").toJson()
    private fun prepare(endsAt: Long? = null) {
        node = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), room, "https://node.example", service.pubkey, now, endsAt).toJson(), now))
        store.save(NodeCustody.read(buildJsonObject {
            put("v", 1); put("node", node.wire); put("participant", owner.pubkey); put("deviceKey", device.secretKeyForStorage().toHex()); put("credential", credential(owner, 4)); put("secret", secret.toHex()); put("rootKey", root.secretKeyForStorage().toHex()); put("phase", "creating")
        }))
    }
    private fun keeper() = NodeKeeperAdmissions(node, owner.pubkey, store, { event, signer ->
        assertEquals(root.pubkey, signer.pubkey)
        val saved = NodeKeeperState.read(requireNotNull(store.custody(owner.pubkey, room)).keeper, node)
        assertTrue(saved.devices.any { it.grant.event.id == event.id })
        posted += event
        if (fail) throw NodeRequestException()
    }, { true }, { clock })
    @Test fun `unknown participant needs approval then later devices stop at five`(): Unit = runBlocking {
        prepare(); val keeper = keeper()
        assertNull(keeper.admit(credential(guest, 8))); assertTrue(posted.isEmpty())
        assertNotNull(keeper.admit(credential(guest, 8), manuallyApproved = true))
        for (seed in 9..12) assertNotNull(keeper.admit(credential(guest, seed)))
        assertNull(keeper.admit(credential(guest, 13))); assertEquals(5, keeper.state().devices.size)
        assertEquals(listOf(guest.pubkey), keeper.state().members)
        assertTrue(keeper.state().devices.all { it.acknowledged })
    }
    @Test fun `short room lost ACK retries the identical signed grant across restart`(): Unit = runBlocking {
        prepare(now + 3600); fail = true
        assertFailsWith<NodeRequestException> { keeper().admit(credential(guest, 8), manuallyApproved = true) }
        val first = posted.single(); assertFalse(keeper().state().devices.single().acknowledged)
        clock += 1; fail = false
        assertNotNull(keeper().admit(credential(guest, 8)))
        assertEquals(first, posted.last()); assertEquals(first.expiration(), now + 3600)
        keeper().renewDue(); assertEquals(2, posted.size)
    }
    @Test fun `removal persists and confirms every revocation before success and cannot be undone`(): Unit = runBlocking {
        prepare(); keeper().admit(credential(guest, 8), true); keeper().admit(credential(guest, 9))
        fail = true; assertFailsWith<NodeRequestException> { keeper().remove(guest.pubkey) }
        val pending = keeper().state(); assertEquals(listOf(guest.pubkey), pending.removed)
        assertTrue(pending.devices.all { it.grant.status == "revoked" }); val ids = pending.devices.map { it.grant.event.id }
        fail = false; keeper().remove(guest.pubkey)
        assertEquals(ids, keeper().state().devices.map { it.grant.event.id }); assertTrue(keeper().state().devices.all { it.acknowledged })
        assertNull(keeper().admit(credential(guest, 10), true))
        val saved = requireNotNull(store.custody(owner.pubkey, room))
        assertFailsWith<IllegalArgumentException> { store.save(saved.changed { put("keeper", NodeKeeperState(emptyList(), emptyList(), emptyList()).toJson()) }) }
    }
    @Test fun `persistent grants renew after ten days with the same id and no repeated resigning`(): Unit = runBlocking {
        prepare(); val first = requireNotNull(keeper().admit(credential(guest, 8), true)).grant
        keeper().renewDue(); assertEquals(1, posted.size)
        clock += 10 * 86400; keeper().renewDue()
        val next = keeper().state().devices.single().grant
        assertEquals(first.grantId, next.grantId); assertNotEquals(first.event.id, next.event.id); assertEquals(clock + NODE_HORIZON_SECONDS, next.expiration)
        keeper().renewDue(); assertEquals(2, posted.size)
    }
    private fun GroupEvent.expiration() = tags.single { it[0] == "expiration" }[1].toLong()
}
