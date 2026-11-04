package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class NodeDeviceAccessTest {
    private val now = 1_900_000_000L
    private val person = LocalSigner(ByteArray(32) { 2 }); private val root = LocalSigner(ByteArray(32) { 3 })
    private val device = LocalSigner(ByteArray(32) { 4 }); private val service = LocalSigner(ByteArray(32) { 5 })
    private val secret = ByteArray(32) { 6 }; private val room = deriveRoom(secret).roomId
    private val node = requireNotNull(NodeRooms.read(NodeRooms.sign(root.secretKeyForStorage(), room, "https://node.example", service.pubkey, now).toJson(), now))
    private val grant = buildGrant(root.secretKeyForStorage(), room, device.pubkey, "node.example", listOf("turn", "relay-read", "relay-write"), null, "ab".repeat(16), now, now + 3600)
    private val memory = NodeMemoryStorage(); private val store = NodeStore(NodeMemoryStorage(), memory, NodeMemoryStorage())
    private var result: NodeAccess = NodeAccess.Active((parseGroupEvent(grant.toJson(), now) as GroupEventVerdict.Ok).parsed as ParsedGrant, "a".repeat(43), now + 3600)
    private var allowed = true; private var unavailable = 0; private var removed = 0; private var closed = 0; private var offline = false
    private var duringQuery: () -> Unit = {}
    private fun controller() = NodeDeviceAccess(node, person.pubkey, device.pubkey, store, { id ->
        assertEquals("ab".repeat(16), id); duringQuery(); if (offline) throw NodeRequestException(); result
    }, { allowed }, { unavailable++ }, { removed++ }, { closed++ }, { now + 2 })
    private fun prepare() {
        val credential = Events.sign(person.secretKeyForStorage(), KIND_DEVICE_CREDENTIAL, now, listOf(listOf("d", room), listOf("device", device.pubkey), listOf("expiration", (now + 3600).toString())), "")
        store.save(NodeCustody.read(buildJsonObject { put("v", 1); put("node", node.wire); put("participant", person.pubkey); put("deviceKey", device.secretKeyForStorage().toHex()); put("credential", credential.toJson()); put("secret", secret.toHex()); put("phase", "admitted"); put("grant", grant.toJson()) }))
    }
    @Test fun `identical active polls work and a failed commit never releases access`(): Unit = runBlocking {
        prepare(); val access = controller(); assertIs<NodeAccess.Active>(access.refresh()); assertTrue(access.permitted())
        assertIs<NodeAccess.Active>(access.refresh()); assertNotNull(store.custody(person.pubkey, room)?.receiver)
        memory.refuse = true; assertFailsWith<IllegalStateException> { access.refresh() }; assertFalse(access.permitted()); assertTrue(unavailable > 0)
        assertEquals(0, removed + closed)
    }
    @Test fun `restart refuses stale active grant after terminal own revocation`(): Unit = runBlocking {
        prepare(); controller().refresh()
        val revoke = buildGrant(root.secretKeyForStorage(), room, device.pubkey, "node.example", listOf("turn", "relay-read", "relay-write"), null, "ab".repeat(16), now + 1, now + 3600, "revoked")
        result = NodeAccess.Removed((parseGroupEvent(revoke.toJson(), now + 2) as GroupEventVerdict.Ok).parsed as ParsedGrant)
        assertIs<NodeAccess.Removed>(controller().refresh()); assertEquals(1, removed)
        result = NodeAccess.Active((parseGroupEvent(grant.toJson(), now) as GroupEventVerdict.Ok).parsed as ParsedGrant, "a".repeat(43), now + 3600)
        val restarted = controller(); assertFailsWith<IllegalArgumentException> { restarted.refresh() }; assertFalse(restarted.permitted()); assertEquals(0, closed)
    }
    @Test fun `offline retains admission while signed reset blocks and explicit closure wipes`(): Unit = runBlocking {
        prepare(); val access = controller(); access.refresh(); offline = true
        assertFailsWith<NodeRequestException> { access.refresh() }; assertTrue(access.permitted()); assertEquals(0, removed + closed)
        offline = false
        result = NodeAccess.Reset((parseGroupEvent(buildReset(root.secretKeyForStorage(), room, "node.example", now + 1).toJson(), now + 2) as GroupEventVerdict.Ok).parsed as ParsedReset)
        assertIs<NodeAccess.Reset>(access.refresh()); assertFalse(access.permitted()); assertEquals(0, removed + closed)
        result = NodeAccess.Closed(NodeRooms.signClosure(node, root.secretKeyForStorage(), "cd".repeat(16), now + 1))
        assertIs<NodeAccess.Closed>(access.refresh()); assertEquals(1, closed); assertEquals(0, removed)
    }
    @Test fun `polling merges into current custody and never recreates a concurrently forgotten room`(): Unit = runBlocking {
        prepare()
        duringQuery = { store.update(person.pubkey, room) { it.changed { put("epochHint", 7) } } }
        val access = controller(); access.refresh()
        assertEquals(7, requireNotNull(store.custody(person.pubkey, room)).epochHint)
        duringQuery = { store.forgetRoom(room) }
        assertFailsWith<IllegalArgumentException> { access.refresh() }
        assertNull(store.custody(person.pubkey, room)); assertFalse(access.permitted()); assertEquals(0, removed + closed)
    }
    @Test fun `withdrawn owner permission does not query or release previous access`(): Unit = runBlocking {
        prepare(); val access = controller(); access.refresh(); allowed = false
        assertFalse(access.permitted()); assertFailsWith<IllegalStateException> { access.refresh() }; assertEquals(0, removed + closed)
    }
    @Test fun `temporary poll failures retain only the previously validated unexpired admission`(): Unit = runBlocking {
        prepare(); var clock = now + 2; var failure = 0
        val access = NodeDeviceAccess(node, person.pubkey, device.pubkey, store, {
            if (failure != 0) throw NodeRequestException(failure)
            result
        }, { allowed }, { unavailable++ }, { removed++ }, { closed++ }, { clock })
        access.refresh()
        for (status in listOf(408, 425, 429, 500, 503)) {
            failure = status
            assertFailsWith<NodeRequestException> { access.refresh() }
            assertTrue(access.permitted(), "HTTP $status must not revoke an unexpired admission")
            assertEquals(0, unavailable + removed + closed)
        }
        clock = now + 3600
        assertFailsWith<NodeRequestException> { access.refresh() }
        assertFalse(access.permitted()); assertEquals(1, unavailable)
    }
    @Test fun `explicit refusal blocks an existing admission without authorising destruction`(): Unit = runBlocking {
        prepare(); var refuse = false
        val access = NodeDeviceAccess(node, person.pubkey, device.pubkey, store, {
            if (refuse) throw NodeRequestException(403)
            result
        }, { allowed }, { unavailable++ }, { removed++ }, { closed++ }, { now + 2 })
        access.refresh(); refuse = true
        assertFailsWith<NodeRequestException> { access.refresh() }
        assertFalse(access.permitted()); assertEquals(1, unavailable); assertEquals(0, removed + closed)
    }
}
