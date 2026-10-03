package dev.forgesworn.kithmoot.ui

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomNameOp
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.encodeInvitationUrl
import dev.forgesworn.kithmoot.protocol.encodeRoomNameOp
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.session.AssignmentStorage
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import dev.forgesworn.kithmoot.session.RoomWork
import dev.forgesworn.kithmoot.session.encodeChatEvent
import dev.forgesworn.kithmoot.storage.SavedRoom
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A rename read on the room's control channel, carried through the same
 * callbacks `RoomViewModel` wires `RoomWork` to: the title and the saved room
 * take the new name, and the chat says who renamed it exactly once, however
 * many copies arrive. The view model itself needs an Android application, so
 * this drives its state reducers and the saved room directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedRoomNameTest {
    @Test fun a_received_rename_retitles_the_room_and_saved_room_and_adds_one_chat_line() = runTest {
        val now = 200L
        val secret = Entropy.bytes(32)
        val roomId = deriveRoom(secret).roomId
        val roomKey = deriveRoom(secret).roomKey
        val me = PrimaryIdentity.create(roomId, 1000, 100)
        val them = PrimaryIdentity.create(roomId, 1000, 100)
        val host = createRoomInvitation(true)
        var saved = SavedRoom.create(secret, me, encodeInvitationUrl("https://kithmoot.example/j/", host.invitation, listOf("wss://r.example")),
            listOf("wss://r.example"), "Name on the link", now, host, host.invitation.canonicalInviter)
        var state = RoomState(roomId = roomId, name = saved.name)

        val incoming = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 8)
        val transport = object : RoomTransport {
            override fun publish(event: NostrEvent) {}
            override fun subscribe(filters: List<Filter>) = incoming
            override suspend fun queryStored(filters: List<Filter>, timeoutMs: Long) = emptyList<NostrEvent>()
            override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long) = true
        }
        val storage = object : AssignmentStorage { var v: String? = null; override suspend fun load() = v; override suspend fun save(encrypted: String) { v = encrypted } }
        val work = RoomWork(roomId, roomKey, me, transport, storage, backgroundScope, now = { now },
            onRoomName = { shared -> saved = saved.withSharedName(shared); state = state.withSharedName(roomId, shared) },
            onRename = { rename -> state = state.withRenameRead(roomId, rename) })
        work.open()

        fun control(op: RoomNameOp, id: String) = encodeChatEvent(encodeRoomNameOp(op), them.participant, them.credential, roomId, roomKey,
            them.deviceSecretKey, now, id = id, channel = "control")
        val op = RoomNameOp("Book club", "0123456789abcdef0123456789abcdef", 199_000)
        incoming.emit(control(op, "first")); runCurrent()
        incoming.emit(control(op, "first")); runCurrent()
        incoming.emit(control(op.copy(carried = true), "carried")); runCurrent()

        assertEquals("Book club", state.name)
        assertEquals("Book club", saved.name)
        assertEquals(op.id, saved.sharedName?.id)
        assertEquals(listOf(RoomNote(op.id, them.participant, "Book club", now)), state.roomNotes)
        assertEquals(state, state.withRenameRead("f".repeat(64), work.roomName()!!.copy(id = "e".repeat(32))), "another room's rename is not this room's line")
        work.close()
    }
}
