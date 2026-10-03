package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomNameOp
import dev.forgesworn.kithmoot.protocol.RoomNameRecord
import dev.forgesworn.kithmoot.protocol.decodeRoomNameOp
import dev.forgesworn.kithmoot.protocol.encodeRoomNameOp
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

/** The room's shared name as `RoomWork` follows it on the control channel. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomWorkNameTest {
    private val room = "a".repeat(64)
    private val key = ByteArray(32) { 9 }
    private val nextId = "b".repeat(64)
    private val nextKey = ByteArray(32) { 7 }
    private val person = PrimaryIdentity.create(room, 1000, 100, ByteArray(32) { 1 }, ByteArray(32) { 4 })
    private val other = PrimaryIdentity.create(room, 1000, 100, ByteArray(32) { 2 }, ByteArray(32) { 5 })
    private val renameId = "0123456789abcdef0123456789abcdef"

    private class Fixture(val incoming: MutableSharedFlow<NostrEvent>, val sent: MutableList<NostrEvent>, val transport: RoomTransport)
    private fun fixture(): Fixture {
        val incoming = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 32)
        val sent = mutableListOf<NostrEvent>()
        return Fixture(incoming, sent, object : RoomTransport {
            override fun publish(event: NostrEvent) { error("Unconfirmed send") }
            override fun subscribe(filters: List<Filter>) = incoming
            override suspend fun queryStored(filters: List<Filter>, timeoutMs: Long) = emptyList<NostrEvent>()
            override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean { sent.add(event); return true }
        })
    }
    private val storage = object : AssignmentStorage { var value: String? = null; override suspend fun load() = value; override suspend fun save(encrypted: String) { value = encrypted } }

    private fun control(op: RoomNameOp, sentAt: Long = 200, id: String = op.id + sentAt, roomId: String = room, roomKey: ByteArray = key) =
        encodeChatEvent(encodeRoomNameOp(op), other.participant, other.credential, roomId, roomKey, other.deviceSecretKey, sentAt, id = id, channel = "control", credentialRoomId = room)

    @Test fun a_received_rename_changes_the_name_once_and_says_so_once() = runTest {
        val f = fixture()
        val names = mutableListOf<RoomNameRecord>()
        val renames = mutableListOf<RoomNameRecord>()
        val work = RoomWork(room, key, person, f.transport, storage, backgroundScope, now = { 200 },
            onRoomName = { names += it }, onRename = { renames += it })
        work.open()
        val rename = control(RoomNameOp("Book club", renameId, 199_500))
        f.incoming.emit(rename); runCurrent()
        assertEquals(listOf("Book club"), names.map { it.name })
        assertEquals(other.participant, renames.single().by)
        // The same event again, and a carried copy of it, change nothing.
        f.incoming.emit(rename); runCurrent()
        f.incoming.emit(control(RoomNameOp("Book club", renameId, 199_500, carried = true), id = "carried")); runCurrent()
        assertEquals(1, names.size); assertEquals(1, renames.size)
        // An older rename is said in the chat but does not win.
        f.incoming.emit(control(RoomNameOp("Older", "f".repeat(32), 100_000))); runCurrent()
        assertEquals(1, names.size); assertEquals(2, renames.size)
        assertEquals("Book club", work.roomName()?.name)
        // One stamped after its own message is no rename at all.
        f.incoming.emit(control(RoomNameOp("Pinned", "e".repeat(32), 260_000))); runCurrent()
        assertEquals("Book club", work.roomName()?.name); assertEquals(2, renames.size)
        work.close()
    }

    @Test fun a_rename_made_here_is_confirmed_and_shown_at_once() = runTest {
        val f = fixture()
        val names = mutableListOf<RoomNameRecord>()
        val work = RoomWork(room, key, person, f.transport, storage, backgroundScope, now = { 200 }, nowMs = { 200_250 },
            onRoomName = { names += it })
        work.open()
        val record = work.rename("  Book‮ club ")
        assertEquals("Book club", record.name)
        assertEquals(person.participant, record.by)
        assertEquals(listOf(record), names)
        val message = assertNotNull(decodeChatEvent(f.sent.last(), room, key, 200, channel = "control"))
        assertEquals(200_250, message.sentAtMs)
        assertEquals(RoomNameOp("Book club", record.id, 200_250), decodeRoomNameOp(message.body))
        assertFailsWith<IllegalArgumentException> { work.rename(" ​ ") }
        work.close()
    }

    @Test fun the_name_is_carried_into_a_new_epoch_a_few_seconds_after_the_rekey() = runTest {
        val f = fixture()
        val work = RoomWork(room, key, person, f.transport, storage, backgroundScope, now = { 200 })
        work.open()
        f.incoming.emit(control(RoomNameOp("Book club", renameId, 199_500))); runCurrent()
        advanceTimeBy(61_000)
        assertEquals(1, f.sent.size, "a fresh copy in this epoch's log is not posted again")
        work.rekey(nextId, nextKey, 1)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, f.sent.size, "not before three seconds")
        advanceTimeBy(14_000); runCurrent()
        val carried = assertNotNull(decodeChatEvent(f.sent.last(), nextId, nextKey, 200, channel = "control", credentialRoomId = room))
        assertEquals(RoomNameOp("Book club", renameId, 199_500, carried = true), decodeRoomNameOp(carried.body))
        assertEquals(2, f.sent.size)
        // Its own copy now satisfies the new epoch: no second carry.
        assertFalse(work.carryNameIfDue())
        work.close()
    }

    @Test fun a_kept_name_is_posted_again_once_the_log_has_none() = runTest {
        val f = fixture()
        val kept = RoomNameRecord("Kept", renameId, 1_000, sentAt = 1)
        val work = RoomWork(room, key, person, f.transport, storage, backgroundScope, now = { 200 }, initialRoomName = kept)
        assertEquals("Kept", work.roomName()?.name)
        work.open()
        advanceTimeBy(61_000); runCurrent()
        val carried = assertNotNull(decodeChatEvent(f.sent.last(), room, key, 200, channel = "control"))
        assertEquals(RoomNameOp("Kept", renameId, 1_000, carried = true), decodeRoomNameOp(carried.body))
        work.close()
    }

    @Test fun a_rename_read_under_a_left_epoch_after_its_rekey_stops_counting() = runTest {
        val f = fixture()
        val rekeys = mutableMapOf<Int, Long>()
        val work = RoomWork(room, key, person, f.transport, storage, backgroundScope, now = { 1_000 }, rekeyedAt = { rekeys[it] })
        work.open()
        f.incoming.emit(control(RoomNameOp("Before", renameId, 150_000), sentAt = 150)); runCurrent()
        f.incoming.emit(control(RoomNameOp("After removal", "f".repeat(32), 900_000), sentAt = 900)); runCurrent()
        assertEquals("After removal", work.roomName()?.name)
        rekeys[1] = 200
        work.rekey(nextId, nextKey, 1)
        assertEquals("Before", work.roomName()?.name)
        work.close()
    }
}
