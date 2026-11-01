package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Sending a reply from Android: the fields the web's `ChatLog.prepareSend` writes (kithmoot #506). */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplySendTest {
    private class Store : RoomStorage {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) { bytes = value.clone() }
        override fun reset() { bytes = null }
    }

    @Test fun `a reply names the message it answers and that message's thread`() = runTest {
        val room = Fixtures.room(); val owner = Fixtures.primary(room, 1, 2)
        val relay = FakeRelay()
        val live = RoomSession(room, owner, relay.transport(), backgroundScope, timing = Fixtures.QUIET, now = { 1L })
        live.join(); advanceTimeBy(1_000); runCurrent()
        assertTrue(live.sendChatConfirmed("Who has the keys?"))
        val root = live.chat.value.single()
        assertTrue(live.sendChatConfirmed("Rowan does", replyTo = root))
        val first = live.chat.value.single { it.body == "Rowan does" }
        assertEquals(refOf(root), first.reply)
        assertEquals(refOf(root), first.thread)
        assertTrue(live.sendChatConfirmed("Not since Tuesday", replyTo = first))
        val deeper = live.chat.value.single { it.body == "Not since Tuesday" }
        assertEquals(refOf(first), deeper.reply)
        assertEquals(refOf(root), deeper.thread)
        // What the log shows is decoded from the signed event, so the wire carries both.
        // A reaction says something about a message; it answers nothing.
        assertFailsWith<IllegalArgumentException> {
            live.sendChatConfirmed("👍", reaction = ChatReaction(root.id, root.participant, "👍", true, 1), replyTo = root)
        }
        live.leave()
    }

    @Test fun `a kept reply is not offered for editing, which would lose what it answers`() = runTest {
        val room = Fixtures.room(); val owner = Fixtures.primary(room, 1, 2)
        val relay = FakeRelay()
        val outbox = PendingChatOutbox(Store(), room.roomId, owner.participant, owner.devicePubkey, io = kotlin.coroutines.EmptyCoroutineContext)
        val live = RoomSession(room, owner, relay.transport(), backgroundScope, timing = Fixtures.QUIET, now = { 1L }, chatOutbox = outbox)
        live.join(); advanceTimeBy(1_000); runCurrent()
        assertTrue(live.sendChatDurable("Who has the keys?"))
        val root = live.chat.value.single()
        relay.confirmsPublications = false
        assertFalse(live.sendChatDurable("Rowan does", replyTo = root))
        assertFalse(outbox.items().single().editable)
        // The kept event goes out as it was signed, still answering the root.
        relay.confirmsPublications = true
        assertTrue(live.retryPendingChat())
        val answer = live.chat.value.single { it.body == "Rowan does" }
        assertEquals(refOf(root), answer.reply)
        assertEquals(refOf(root), answer.thread)
        live.leave()
    }
}
