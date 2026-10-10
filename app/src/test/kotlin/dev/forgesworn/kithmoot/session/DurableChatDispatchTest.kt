package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DurableChatDispatchTest {
    private class Store : RoomStorage {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) { bytes = value.clone() }
        override fun reset() { bytes = null }
    }

    @Test fun `refusal preoffer rejection and ambiguous failure hold later durable messages`() = runTest {
        for (failure in listOf("refused", "not-offered", "generic")) {
            val room = Fixtures.room(); val owner = Fixtures.primary(room, 1, 2)
            val relay = FakeRelay(); val attempted = mutableListOf<NostrEvent>()
            val transport = object : RoomTransport by relay.transport() {
                override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
                    stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
                    assertTrue(stillAllowed()); attempted += event
                    when (failure) {
                        "refused" -> return false
                        "not-offered" -> throw PublicationNotOfferedException()
                        else -> error("Ambiguous dispatch failure")
                    }
                }
            }
            val store = Store()
            val outbox = PendingChatOutbox(store, room.roomId, owner.participant, owner.devicePubkey)
            val live = RoomSession(room, owner, transport, backgroundScope, timing = Fixtures.QUIET,
                now = { 1L }, chatOutbox = outbox)
            live.join(); advanceTimeBy(1_000); runCurrent()
            suspend fun send(text: String) {
                if (failure == "generic") assertFailsWith<IllegalStateException> { live.sendChatDurable(text) }
                else assertFalse(live.sendChatDurable(text))
            }
            send("first held"); val original = outbox.items().single().event
            send("second waits")
            assertEquals(listOf(original, original), attempted, failure)
            assertEquals(2, outbox.items().size)
            assertEquals(PendingChatState.WAITING, outbox.items()[1].state)
            assertEquals(when (failure) {
                "refused" -> PendingChatState.REFUSED
                "not-offered" -> PendingChatState.WAITING
                else -> PendingChatState.UNKNOWN
            }, outbox.items()[0].state)
            assertEquals(outbox.items(), PendingChatOutbox(store, room.roomId, owner.participant, owner.devicePubkey).items())
            live.leave()
        }
    }

    @Test fun `admission expiry at dispatch holds a never offered message without crashing`() = runTest {
        val room = Fixtures.room(); val owner = Fixtures.primary(room, 1, 2)
        val issuer = Fixtures.key(90)
        val policy = RoomPolicy(KindredTier.KITH, listOf(Schnorr.publicKeyHex(issuer)))
        val proof = issueKindredProof(issuer, owner.participant, KindredTier.KITH, room.roomId, expiresAt = 10)
        val relay = FakeRelay(); var clock = 1L; var dispatches = 0
        val transport = object : RoomTransport by relay.transport() {
            override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
                stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
                dispatches++; clock = 11
                assertFalse(stillAllowed())
                throw PublicationNotOfferedException()
            }
        }
        val store = Store()
        val outbox = PendingChatOutbox(store, room.roomId, owner.participant, owner.devicePubkey)
        val live = RoomSession(room, owner, transport, backgroundScope, timing = Fixtures.QUIET,
            now = { clock }, policy = policy, proof = proof, chatOutbox = outbox)
        live.join(); advanceTimeBy(1_000); runCurrent()
        assertFalse(live.sendChatDurable("Held at expiry"))
        assertEquals(1, dispatches)
        assertEquals(PendingChatState.MOVED, live.pendingChats.value.single().state)
        assertEquals(PendingChatState.MOVED,
            PendingChatOutbox(store, room.roomId, owner.participant, owner.devicePubkey).pending()!!.state)
        assertFalse(live.retryPendingChat())
        assertEquals(0, relay.countOfKind(KIND_CHAT))
        assertTrue(live.deletePendingChat(live.pendingChats.value.single().id))
    }

    @Test fun `generic error after offer remains uncertain across reopen and later guard expiry`() = runTest {
        val room = Fixtures.room(); val owner = Fixtures.primary(room, 1, 2)
        val issuer = Fixtures.key(90)
        val policy = RoomPolicy(KindredTier.KITH, listOf(Schnorr.publicKeyHex(issuer)))
        val proof = issueKindredProof(issuer, owner.participant, KindredTier.KITH, room.roomId, expiresAt = 10)
        val relay = FakeRelay(); var clock = 1L; var reject = false
        val offered = mutableListOf<NostrEvent>()
        val transport = object : RoomTransport by relay.transport() {
            override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
                stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
                if (reject) {
                    clock = 11; assertFalse(stillAllowed()); throw PublicationNotOfferedException()
                }
                assertTrue(stillAllowed()); offered += event
                error("Handoff failed after offer")
            }
        }
        val store = Store()
        val outbox = PendingChatOutbox(store, room.roomId, owner.participant, owner.devicePubkey)
        val live = RoomSession(room, owner, transport, backgroundScope, timing = Fixtures.QUIET,
            now = { clock }, policy = policy, proof = proof, chatOutbox = outbox)
        live.join(); advanceTimeBy(1_000); runCurrent()
        assertFailsWith<IllegalStateException> { live.sendChatDurable("May have arrived") }
        val original = outbox.pending()!!
        assertEquals(PendingChatState.UNKNOWN, original.state)
        assertEquals(original.event, offered.single())
        assertEquals(PendingChatState.UNKNOWN,
            PendingChatOutbox(store, room.roomId, owner.participant, owner.devicePubkey).pending()!!.state)
        reject = true
        assertFalse(live.retryPendingChat())
        assertEquals(PendingChatState.UNKNOWN, live.pendingChats.value.single().state)
        assertEquals(original.event, outbox.pending()!!.event)
        assertFalse(live.deletePendingChat(original.event.id))
        assertNull(live.editPendingChat(original.event.id))
        assertEquals(1, offered.size)
        println("DURABLE_CHAT_DISPATCH_MEASUREMENT " +
            "{\"case\":\"ambiguous-offer-then-expired-guard\",\"event\":\"${original.event.id}\",\"offers\":1,\"state\":\"UNKNOWN\",\"editable\":false}")
    }
}
