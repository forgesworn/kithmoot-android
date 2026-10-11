package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RoomWorkLogoTest {
    private val room = "a".repeat(64); private val key = ByteArray(32) { 9 }
    private val nextRoom = "b".repeat(64); private val nextKey = ByteArray(32) { 7 }
    private val person = PrimaryIdentity.create(room, 2_000, 100, ByteArray(32) { 1 }, ByteArray(32) { 4 })
    private val other = PrimaryIdentity.create(room, 2_000, 100, ByteArray(32) { 2 }, ByteArray(32) { 5 })
    private val image = requireNotNull(LogoImages.read(Json.parseToJsonElement(requireNotNull(javaClass
        .getResourceAsStream("/room-logo-chat-v1.json")).bufferedReader().use { it.readText() }).jsonObject
        .getValue("vectors").jsonArray.first().jsonObject.getValue("expected").jsonObject.getValue("roomLogo")))
    private val id = "0123456789abcdef0123456789abcdef"
    private val storage get() = object : AssignmentStorage {
        var data: String? = null
        override suspend fun load() = data
        override suspend fun save(encrypted: String) { data = encrypted }
    }

    private class Transport : RoomTransport {
        val incoming = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 32)
        val sent = mutableListOf<NostrEvent>()
        var gate: CompletableDeferred<Unit>? = null
        val waiting = CompletableDeferred<Unit>()
        override fun publish(event: NostrEvent) { error("Unconfirmed send") }
        override fun subscribe(filters: List<Filter>) = incoming
        override suspend fun queryStored(filters: List<Filter>, timeoutMs: Long) = emptyList<NostrEvent>()
        override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean { sent += event; return true }
        override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long, stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
            waiting.complete(Unit); gate?.await()
            if (!stillAllowed()) return false
            sent += event
            return true
        }
    }

    private fun control(logo: LogoImage?, at: Long = 199_500, sent: Long = at / 1000) = encodeChatEvent(
        encodeRoomLogoOp(roomLogoOp(logo, at, id)), other.participant, other.credential, room, key,
        other.deviceSecretKey, sent, channel = "control", roomLogo = RoomLogoPayload(logo),
    )

    @Test fun `received images and removals update metadata once without ordinary chat`() = runTest {
        val transport = Transport(); val changes = mutableListOf<RoomLogoRecord?>()
        val work = RoomWork(room, key, person, transport, storage, backgroundScope, now = { 200 }, onRoomLogo = { logo, _ -> changes += logo })
        work.open()
        val event = control(image)
        transport.incoming.emit(event); runCurrent()
        transport.incoming.emit(event); runCurrent()
        assertEquals(1, changes.size)
        assertEquals(image.sha256, work.roomLogo()?.image?.sha256)
        assertEquals(other.participant, changes.single()?.by)
        transport.incoming.emit(control(null, 200_000)); runCurrent()
        assertEquals(2, changes.size)
        assertNull(assertNotNull(work.roomLogo()).image)
        assertNull(decodeChatEvent(event, room, key, 200))
        work.close()
    }

    @Test fun `a confirmed local override and removal are immediately available`() = runTest {
        val transport = Transport(); var time = 200_250L
        val work = RoomWork(room, key, person, transport, storage, backgroundScope, now = { 200 }, nowMs = { time })
        work.open()
        val chosen = work.replaceRoomLogo(image)
        assertEquals(chosen, work.roomLogo())
        assertEquals(person.participant, chosen.by)
        val decoded = assertNotNull(decodeChatEvent(transport.sent.last(), room, key, 200, channel = "control"))
        assertEquals(time, decoded.sentAtMs)
        assertEquals(image.toJson(), decoded.roomLogo?.image?.toJson())
        time++
        work.replaceRoomLogo(null)
        assertNull(assertNotNull(work.roomLogo()).image)
        work.close()
    }

    @Test fun `a delayed write is refused when the room closes`() = runTest {
        val transport = Transport(); transport.gate = CompletableDeferred()
        val work = RoomWork(room, key, person, transport, storage, backgroundScope, now = { 200 }, nowMs = { 200_000 })
        work.open()
        val writing = async { runCatching { work.replaceRoomLogo(image) } }
        transport.waiting.await(); work.close(); transport.gate!!.complete(Unit)
        assertIs<IllegalStateException>(writing.await().exceptionOrNull())
        assertEquals(1, transport.sent.size, "Only discovery was sent; the logo was never offered")
        assertNull(work.roomLogo())
    }

    @Test fun `a delayed write cannot cross the rekey into new traffic keys`() = runTest {
        val transport = Transport(); transport.gate = CompletableDeferred()
        val work = RoomWork(room, key, person, transport, storage, backgroundScope, now = { 200 }, nowMs = { 200_000 })
        work.open()
        val writing = async { runCatching { work.replaceRoomLogo(image) } }
        transport.waiting.await(); work.rekey(nextRoom, nextKey, 1); transport.gate!!.complete(Unit)
        assertIs<IllegalStateException>(writing.await().exceptionOrNull())
        assertEquals(1, transport.sent.size)
        assertNull(work.roomLogo())
        work.close()
    }

    @Test fun `cancelling the editor before dispatch sends no logo`() = runTest {
        val transport = Transport(); transport.gate = CompletableDeferred()
        val work = RoomWork(room, key, person, transport, storage, backgroundScope, now = { 200 }, nowMs = { 200_000 })
        work.open()
        var editorOpen = true
        val writing = async { runCatching { work.replaceRoomLogo(image) { editorOpen } } }
        transport.waiting.await(); editorOpen = false; transport.gate!!.complete(Unit)
        assertIs<IllegalStateException>(writing.await().exceptionOrNull())
        assertEquals(1, transport.sent.size, "Only discovery was sent")
        assertNull(work.roomLogo()); work.close()
    }

    @Test fun `an already invalid editor does not enter publication`() = runTest {
        val transport = Transport()
        val work = RoomWork(room, key, person, transport, storage, backgroundScope, now = { 200 })
        work.open()
        assertFailsWith<IllegalStateException> { work.replaceRoomLogo(null) { false } }
        assertFalse(transport.waiting.isCompleted); assertEquals(1, transport.sent.size)
        work.close()
    }

    @Test fun `a removed epoch logo disappears and is not carried into the new epoch`() = runTest {
        val transport = Transport(); val changes = mutableListOf<RoomLogoRecord?>()
        val rekeys = mutableMapOf<Int, Long>()
        val work = RoomWork(room, key, person, transport, storage, backgroundScope, now = { 1_000 },
            rekeyedAt = { rekeys[it] }, onRoomLogo = { logo, _ -> changes += logo })
        work.open(); transport.incoming.emit(control(image, 900_000)); runCurrent()
        assertNotNull(work.roomLogo())
        rekeys[1] = 200; work.rekey(nextRoom, nextKey, 1)
        assertNull(work.roomLogo()); assertNull(changes.last())
        assertFalse(work.carryLogoIfDue())
        work.close()
    }

    @Test fun `a cached removal survives rekey and a carried copy retains its original order`() = runTest {
        val transport = Transport(); val kept = RoomLogoRecord(null, id, 100_000, 100)
        val changes = mutableListOf<Pair<RoomLogoRecord?, Int>>()
        val work = RoomWork(room, key, person, transport, storage, backgroundScope, now = { 200 },
            initialRoomLogo = kept, initialRoomLogoEpoch = 0, onRoomLogo = { logo, epoch -> changes += logo to epoch })
        work.open(); work.rekey(nextRoom, nextKey, 1)
        assertEquals(listOf(0, 1), changes.map { it.second })
        assertTrue(changes.all { it.first?.id == kept.id })
        assertTrue(work.carryLogoIfDue())
        val carried = assertNotNull(decodeChatEvent(transport.sent.last(), nextRoom, nextKey, 200,
            channel = "control", credentialRoomId = room))
        assertNull(assertNotNull(carried.roomLogo).image)
        val op = assertNotNull(decodeRoomLogoOp(carried.body))
        assertEquals(100_000, op.at); assertEquals(id, op.id); assertTrue(op.carried)
        assertFalse(work.carryLogoIfDue())
        work.close()
    }

    @Test fun `opening after rekey reports an invalidated cached seed with the current epoch`() = runTest {
        val transport = Transport(); val changes = mutableListOf<Pair<RoomLogoRecord?, Int>>()
        val kept = RoomLogoRecord(image, id, 900_000, 900)
        val work = RoomWork(room, key, person, transport, storage, backgroundScope, now = { 1_000 },
            initialEpoch = 1, initialRoomLogo = kept, initialRoomLogoEpoch = 0,
            rekeyedAt = { 200 }, onRoomLogo = { logo, epoch -> changes += logo to epoch })
        work.open()
        assertEquals(listOf<Pair<RoomLogoRecord?, Int>>(null to 1), changes)
        assertNull(work.roomLogo()); assertFalse(work.carryLogoIfDue())
        work.close()
    }
}
