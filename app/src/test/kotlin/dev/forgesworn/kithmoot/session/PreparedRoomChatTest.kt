package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PreparedRoomChatTest {
    private class Store : RoomStorage {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) { bytes = value.clone() }
        override fun reset() { bytes = null }
    }

    @Test fun `non retaining confirmation retries the exact message without an outbox`() = runTest {
        val room = Fixtures.room(); val owner = Fixtures.primary(room, 1, 2)
        val relay = FakeRelay(); val offers = mutableListOf<NostrEvent>(); var confirm = false
        val transport = object : RoomTransport by relay.transport() {
            override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
                stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
                assertTrue(stillAllowed()); offers += event; return confirm
            }
        }
        val live = RoomSession(room, owner, transport, backgroundScope, timing = Fixtures.QUIET, now = { 1L })
        live.join(); advanceTimeBy(1_000); runCurrent()
        val prepared = requireNotNull(live.prepareChatForSend("Synthetic quiet recording"))
        assertFalse(live.sendPreparedChatConfirmed(prepared) { true })
        assertTrue(live.chat.value.isEmpty()); assertFalse(live.pendingChat())
        confirm = true
        assertTrue(live.sendPreparedChatConfirmed(prepared) { true })
        assertEquals(listOf(prepared.pending.event, prepared.pending.event), offers)
        assertEquals(1, live.chat.value.size); assertFalse(live.pendingChat())
        assertFailsWith<IllegalStateException> { live.sendPreparedChatConfirmed(prepared) { false } }
        assertEquals(2, offers.size)
        live.leave()
    }

    @Test fun `non retaining publication rechecks ownership at the transport boundary`() = runTest {
        val room = Fixtures.room(); val owner = Fixtures.primary(room, 1, 2)
        val relay = FakeRelay(); var owned = true
        val transport = object : RoomTransport by relay.transport() {
            override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
                stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
                owned = false; assertFalse(stillAllowed()); throw PublicationNotOfferedException()
            }
        }
        val live = RoomSession(room, owner, transport, backgroundScope, timing = Fixtures.QUIET, now = { 1L })
        live.join(); advanceTimeBy(1_000); runCurrent()
        val prepared = requireNotNull(live.prepareChatForSend("Synthetic quiet recording"))
        assertFailsWith<PublicationNotOfferedException> { live.sendPreparedChatConfirmed(prepared) { owned } }
        assertTrue(live.chat.value.isEmpty()); assertFalse(live.pendingChat())
        live.leave()
    }

    @Test fun `prepare is offline and failed draft cleanup retries the same signed recording without duplicate chat`() = runTest {
        val room = Fixtures.room(); val owner = Fixtures.primary(room, 1, 2)
        val relay = FakeRelay(); val offers = mutableListOf<NostrEvent>()
        val transport = object : RoomTransport by relay.transport() {
            override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
                stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
                assertTrue(stillAllowed()); offers += event; return true
            }
        }
        val storage = Store(); val outbox = PendingChatOutbox(storage, room.roomId, owner.participant, owner.devicePubkey)
        val live = RoomSession(room, owner, transport, backgroundScope, timing = Fixtures.QUIET, now = { 1L }, chatOutbox = outbox)
        live.join(); advanceTimeBy(1_000); runCurrent()
        val attachment = ChatAttachment("https://private.example/${"ab".repeat(32)}", "ab".repeat(32),
            "cd".repeat(32), "Synthetic recording.mp4", "video/mp4", 100L)
        val prepared = requireNotNull(live.prepareChatForSend(attachment.name!!, attachments = listOf(attachment)))
        assertFalse(prepared.pending.editable)
        assertTrue(outbox.items().isEmpty()); assertTrue(offers.isEmpty())
        assertFailsWith<IllegalStateException> {
            live.sendPreparedChatDurable(prepared, onRetained = { error("Synthetic draft cleanup failed") })
        }
        assertEquals(prepared.pending, PendingChatOutbox(storage, room.roomId, owner.participant, owner.devicePubkey).items().single())
        assertTrue(offers.isEmpty())
        assertTrue(live.sendPreparedChatDurable(prepared))
        assertEquals(listOf(prepared.pending.event), offers)
        assertEquals(listOf(attachment), live.chat.value.single().attachments)
        assertTrue(outbox.items().isEmpty())
        // Confirmation can remove the outbox row before a draft owner records
        // its cleanup. A further recovery retry still offers the same event.
        assertTrue(live.sendPreparedChatDurable(prepared))
        assertEquals(listOf(prepared.pending.event, prepared.pending.event), offers)
        assertEquals(1, live.chat.value.size)
        assertEquals(prepared.pending.messageId, live.chat.value.single().id)
        live.leave()
    }

    @Test fun `another original room or identity and revoked draft guard cannot hand off or publish`() = runTest {
        val room = Fixtures.room(); val owner = Fixtures.primary(room, 1, 2)
        val relay = FakeRelay(); var offers = 0
        val transport = object : RoomTransport by relay.transport() {
            override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
                stillAllowed: () -> Boolean, timeoutMs: Long): Boolean { offers++; return true }
        }
        val outbox = PendingChatOutbox(Store(), room.roomId, owner.participant, owner.devicePubkey)
        val live = RoomSession(room, owner, transport, backgroundScope, timing = Fixtures.QUIET, now = { 1L }, chatOutbox = outbox)
        live.join(); advanceTimeBy(1_000); runCurrent()
        val prepared = requireNotNull(live.prepareChatForSend("Synthetic recording"))
        assertFailsWith<IllegalArgumentException> { live.sendPreparedChatDurable(prepared.copy(room = "ef".repeat(32))) }
        assertFailsWith<IllegalArgumentException> { live.sendPreparedChatDurable(prepared.copy(participant = "ef".repeat(32))) }
        assertFailsWith<IllegalStateException> {
            live.sendPreparedChatDurable(prepared, commitGuard = { error("Original recording forgotten") })
        }
        assertTrue(outbox.items().isEmpty()); assertEquals(0, offers)
        live.leave()
    }
}
