package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomStorageRecord
import dev.forgesworn.kithmoot.protocol.buildGrant
import dev.forgesworn.kithmoot.protocol.encodeRoomStorageOp
import dev.forgesworn.kithmoot.protocol.signRoomStorage
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A room's storage list and its keeper's grants as `RoomWork` reads them off the control channel (S5b). */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomWorkStorageTest {
    private val room = "2848bdd5c60a79e2dfe667ae9616c1107f61709032b66e556e28f4a96e69450b"
    private val key = ByteArray(32) { 9 }
    private val authoritySk = ByteArray(32) { 6 }
    private val authority = Schnorr.publicKeyHex(authoritySk)
    private val person = PrimaryIdentity.create(room, 1000, 100, ByteArray(32) { 1 }, ByteArray(32) { 4 })
    private val other = PrimaryIdentity.create(room, 1000, 100, ByteArray(32) { 2 }, ByteArray(32) { 5 })
    private val incoming = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 32)
    private val sent = mutableListOf<NostrEvent>()
    private val transport = object : RoomTransport {
        override fun publish(event: NostrEvent) { sent.add(event) }
        override fun subscribe(filters: List<Filter>) = incoming
        override suspend fun queryStored(filters: List<Filter>, timeoutMs: Long) = emptyList<NostrEvent>()
        override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean { sent.add(event); return true }
    }
    private val storage = object : AssignmentStorage { var value: String? = null; override suspend fun load() = value; override suspend fun save(encrypted: String) { value = encrypted } }

    private fun control(body: String, id: String) =
        encodeChatEvent(body, other.participant, other.credential, room, key, other.deviceSecretKey, 200, id = id, channel = "control", credentialRoomId = room)

    private fun op(nodes: List<String>, version: Long, signer: ByteArray = authoritySk) =
        RoomStorageRecord(nodes, version, signRoomStorage(room, version, nodes, signer))

    @Test fun `only the authority's newest list is taken, from whoever posts it`() = runTest {
        val taken = mutableListOf<RoomStorageRecord>()
        val work = RoomWork(room, key, person, transport, storage, backgroundScope, now = { 200 }, authority = authority,
            onRoomStorage = { record, _ -> taken += record })
        work.open()
        val listed = op(listOf("https://grove.example.net"), 10)
        incoming.emit(control(encodeRoomStorageOp(listed), "a".repeat(64))); runCurrent()
        assertEquals(listOf(listed), taken)
        // The same again, an older one, and one signed by somebody else change nothing.
        incoming.emit(control(encodeRoomStorageOp(listed), "b".repeat(64))); runCurrent()
        incoming.emit(control(encodeRoomStorageOp(op(listOf("https://heath.example.net"), 9)), "c".repeat(64))); runCurrent()
        incoming.emit(control(encodeRoomStorageOp(op(listOf("https://heath.example.net"), 12, ByteArray(32) { 8 })), "d".repeat(64))); runCurrent()
        assertEquals(1, taken.size)
        // A withdrawal is newer, and taken.
        incoming.emit(control(encodeRoomStorageOp(op(emptyList(), 11)), "e".repeat(64))); runCurrent()
        assertEquals(emptyList(), taken.last().nodes)
        work.close()
    }

    @Test fun `a grant op is handed over whole, and asking for grants names nothing`() = runTest {
        val grants = mutableListOf<JsonElement>()
        val work = RoomWork(room, key, person, transport, storage, backgroundScope, now = { 200 }, authority = authority,
            onStorageGrant = { grants += it })
        work.open()
        val grant = buildGrant(authoritySk, room, person.devicePubkey(), "grove.example.net", listOf("get"), null, "ab".repeat(16), 200, 1000)
        val body = buildJsonObject { put("op", "storage-grant"); put("event", grant.toJson()) }.toString()
        incoming.emit(control(body, "f".repeat(64))); runCurrent()
        assertEquals(listOf<JsonElement>(grant.toJson()), grants)
        // An op with anything else beside the event is not one.
        incoming.emit(control(buildJsonObject { put("op", "storage-grant"); put("event", grant.toJson()); put("x", 1) }.toString(), "9".repeat(64))); runCurrent()
        assertEquals(1, grants.size)
        sent.clear()
        assertTrue(work.askForStorageGrants())
        val asked = assertNotNull(decodeChatEvent(sent.single(), room, key, 200, channel = "control"))
        assertEquals("{\"op\":\"storage-grant?\"}", asked.body)
        work.close()
    }
}

private fun PrimaryIdentity.devicePubkey(): String = Schnorr.publicKeyHex(deviceSecretKey)
