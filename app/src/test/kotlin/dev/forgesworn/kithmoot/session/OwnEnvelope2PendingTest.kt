package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.EpochKeys
import dev.forgesworn.kithmoot.protocol.KindredTier
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.issueKindredProof
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.service.FlushOutcome
import dev.forgesworn.kithmoot.service.flushPending
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * An envelope-2 message of this device's own, where the app assumed the device key signed every
 * chat event it kept: the outbox, the prepared (recording) message, the publication deadline and
 * the session's send and retry. The events are built in `ChatEvents` from the primitives; nothing
 * here, or in the app, writes envelope 2.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnEnvelope2PendingTest {
    private val room = Fixtures.room()
    private val me = Fixtures.primary(room, 1, 2)
    private val other = Fixtures.primary(room, 40, 41)
    private val main = ChatEvents.Stream(room.roomId, room.roomKey)
    private val epoch = deriveEpoch(RoomEpoch(1, ByteArray(32) { (90 + it).toByte() }))
    private val epochMain = ChatEvents.Stream(epoch.id, epoch.key)
    private val id = "ab".repeat(16)

    private class Store : RoomStorage {
        var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) { bytes = value.clone() }
        override fun reset() { bytes = null }
    }

    private fun outbox(store: Store = Store()) = PendingChatOutbox(store, room.roomId, me.participant, me.devicePubkey)
    private fun pending(event: NostrEvent, stream: ChatEvents.Stream = main, outer: Boolean = true, messageId: String = id) =
        PendingChatOutbox.Pending(stream.id, event, text = "hello", messageId = messageId, outer = outer)
    private fun prepared(event: NostrEvent, stream: ChatEvents.Stream = main, outer: Boolean = true) =
        PreparedRoomChat(room.roomId, me.participant, me.devicePubkey, pending(event, stream, outer))
    private fun journal(store: Store) = Json.parseToJsonElement(store.bytes!!.toString(Charsets.UTF_8)).jsonObject

    // --- originalChatPublicationDeadline ---------------------------------------------------------------------------

    @Test fun `the publication deadline of an envelope 2 message is read from its body, as for envelope 1`() {
        val issuer = Fixtures.key(8)
        fun proof(expires: Long) = issueKindredProof(issuer, me.participant, KindredTier.KITH, room.roomId, expiresAt = expires)
        // Credential expiry 100000; the proof's and the room's end are earlier or later; the least wins.
        val cases = listOf(
            Triple(null, null, Fixtures.CREDENTIAL_EXPIRY),
            Triple(proof(500), null, 500L),
            Triple(null, 300L, 300L),
            Triple(proof(500), 300L, 300L),
            Triple(proof(200), 900L, 200L),
        )
        for ((p, ends, want) in cases) {
            val v1 = ChatEvents.v1(room, me, main, id, proof = p, roomEnds = ends)
            val v2 = ChatEvents.v2(room, me, main, id, proof = p, roomEnds = ends)
            assertEquals(want, originalChatPublicationDeadline(v1, room.roomId, room.roomKey), "envelope 1 $p $ends")
            assertEquals(want, originalChatPublicationDeadline(v2, room.roomId, room.roomKey), "envelope 2 $p $ends")
        }
        // On an epoch's stream the key is the epoch's, and the credential still the room's.
        val inEpoch = ChatEvents.v2(room, me, epochMain, id)
        assertEquals(Fixtures.CREDENTIAL_EXPIRY, originalChatPublicationDeadline(inEpoch, epoch.id, epoch.key))
    }

    @Test fun `a malformed envelope 2 still has no deadline`() {
        val good = ChatEvents.v2(room, me, main, id)
        val plain = Json.parseToJsonElement(dev.forgesworn.kithmoot.crypto.Nip44.decrypt(good.content, main.key)).jsonObject
        for (wrap in listOf<(JsonObject) -> JsonObject>(
            { JsonObject(it + ("extra" to JsonPrimitive(1))) }, { JsonObject(it + ("v" to JsonPrimitive(3))) }, { JsonObject(it - "sig") },
        )) {
            val bad = dev.forgesworn.kithmoot.protocol.Events.sign(Entropy.bytes(32), KIND_CHAT, 100, good.tags,
                dev.forgesworn.kithmoot.crypto.Nip44.encrypt(wrap(plain).toString(), main.key))
            assertFailsWith<IllegalArgumentException> { originalChatPublicationDeadline(bad, room.roomId, room.roomKey) }
        }
    }

    // --- PendingChatOutbox -------------------------------------------------------------------------------------------

    @Test fun `the outbox keeps an own envelope 2 message and reads it back, and an envelope 1 journal is as it was`() = runTest {
        val store = Store()
        val v2 = ChatEvents.v2(room, me, main, id)
        assertNotEquals(me.devicePubkey, v2.pubkey)
        outbox(store).retain(main.id, v2, text = "hello", messageId = id, outer = true)
        val read = outbox(store).items().single()
        assertEquals(v2, read.event)
        assertTrue(read.outer)
        assertEquals(true, journal(store).getValue("items").jsonArray.single().jsonObject["outer"]?.jsonPrimitive?.content?.toBoolean())

        // An envelope 1 message carries no new field, so its journal row is what it always was.
        val v1Store = Store()
        val v1 = ChatEvents.v1(room, me, main, "cd".repeat(16))
        outbox(v1Store).retain(main.id, v1, text = "hello", messageId = "cd".repeat(16))
        assertFalse("outer" in journal(v1Store).getValue("items").jsonArray.single().jsonObject)
        assertFalse(outbox(v1Store).items().single().outer)
    }

    @Test fun `the outbox still refuses an event the device key did not sign unless the session vouched for it`() = runTest {
        val v2 = ChatEvents.v2(room, me, main, id)
        assertFailsWith<IllegalArgumentException> { outbox().retain(main.id, v2, text = "hello", messageId = id) }
        assertFailsWith<IllegalArgumentException> { outbox().retainPrepared(pending(v2, outer = false)) }
        val theirs = ChatEvents.v1(room, other, main, id)
        assertFailsWith<IllegalArgumentException> { outbox().retain(main.id, theirs, text = "hello", messageId = id) }
        // A flagged row is still a signed chat event.
        val tampered = v2.copy(content = v2.content.reversed())
        assertFailsWith<IllegalArgumentException> { outbox().retain(main.id, tampered, text = "hello", messageId = id, outer = true) }
        val notChat = dev.forgesworn.kithmoot.protocol.Events.sign(Entropy.bytes(32), 1, 100, emptyList(), "x")
        assertFailsWith<IllegalArgumentException> { outbox().retain(main.id, notChat, text = "hello", messageId = id, outer = true) }
        assertTrue(outbox().items().isEmpty())
    }

    @Test fun `a handoff of a prepared envelope 2 message is retried without a second row or a changed flag`() = runTest {
        val store = Store(); val v2 = ChatEvents.v2(room, me, main, id)
        val item = pending(v2)
        assertEquals(item, outbox(store).retainPrepared(item))
        assertEquals(item, outbox(store).retainPrepared(item))
        assertEquals(1, outbox(store).items().size)
        assertFailsWith<IllegalArgumentException> { outbox(store).retainPrepared(item.copy(outer = false)) }
    }

    // --- PreparedRoomChat --------------------------------------------------------------------------------------------

    @Test fun `a prepared message may be an envelope 2 the session vouched for, and survives its journal`() {
        val v2 = ChatEvents.v2(room, me, main, id)
        val chat = prepared(v2)
        assertTrue(chat.pending.outer)
        val json = chat.toJson()
        assertEquals(true, json["outer"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(chat, preparedRoomChatFromJson(json))
        assertFailsWith<IllegalArgumentException> { prepared(v2, outer = false) }

        val v1 = prepared(ChatEvents.v1(room, me, main, id), outer = false)
        assertFalse("outer" in v1.toJson())
        assertEquals(v1, preparedRoomChatFromJson(v1.toJson()))
    }

    // --- the session -------------------------------------------------------------------------------------------------

    private fun TestScopeSession(initial: EpochKeys = EpochKeys(0, room.roomId, room.roomKey), relay: FakeRelay, outbox: PendingChatOutbox?, scope: kotlinx.coroutines.CoroutineScope) =
        RoomSession(room, me, relay.transport(), scope, timing = Fixtures.QUIET, now = { 101L }, initialEpoch = initial, chatOutbox = outbox)

    @Test fun `a prepared envelope 2 message of this device is sent confirmed and read back as this device's`() = runTest {
        val relay = FakeRelay()
        val live = TestScopeSession(relay = relay, outbox = null, scope = backgroundScope)
        live.join(); advanceTimeBy(1_000); runCurrent()
        val v2 = ChatEvents.v2(room, me, main, id)
        assertTrue(live.sendPreparedChatConfirmed(prepared(v2)) { true })
        assertTrue(v2 in relay.published)
        val shown = live.chat.value.single()
        assertEquals(me.devicePubkey, shown.device)
        assertEquals(me.participant, shown.participant)
        assertEquals(id, shown.id)
        live.leave()
    }

    @Test fun `a prepared envelope 2 message in a later epoch is sent under that epoch's stream`() = runTest {
        val relay = FakeRelay()
        val live = TestScopeSession(initial = epoch, relay = relay, outbox = null, scope = backgroundScope)
        live.join(); advanceTimeBy(1_000); runCurrent()
        val v2 = ChatEvents.v2(room, me, epochMain, id)
        assertTrue(live.sendPreparedChatConfirmed(prepared(v2, epochMain)) { true })
        assertEquals(listOf(v2), relay.published.filter { it.kind == KIND_CHAT })
        live.leave()
    }

    @Test fun `durable send keeps a refused envelope 2 message in the journal, flagged, and a retry sends it`() = runTest {
        val relay = FakeRelay().also { it.confirmsPublications = false }
        val store = Store()
        val live = TestScopeSession(relay = relay, outbox = outbox(store), scope = backgroundScope)
        live.join(); advanceTimeBy(1_000); runCurrent()
        val v2 = ChatEvents.v2(room, me, main, id)
        assertFalse(live.sendPreparedChatDurable(prepared(v2)))
        // What a restart would read: the same event, flagged, refused.
        val kept = outbox(store).items().single()
        assertEquals(v2, kept.event); assertTrue(kept.outer); assertEquals(PendingChatState.REFUSED, kept.state)
        relay.confirmsPublications = true
        assertTrue(live.retryPendingChat())
        assertTrue(v2 in relay.published)
        assertTrue(outbox(store).items().isEmpty())
        assertEquals(me.devicePubkey, live.chat.value.single().device)
        live.leave()
    }

    @Test fun `an envelope 2 message that is not this device's is refused however it is flagged`() = runTest {
        val relay = FakeRelay(); val store = Store()
        val live = TestScopeSession(relay = relay, outbox = outbox(store), scope = backgroundScope)
        live.join(); advanceTimeBy(1_000); runCurrent()
        val notMine = mapOf(
            // A forgery: the body names this device, the event is signed by a key nobody can recompute.
            "forged" to ChatEvents.v2(room, me, main, id, outerSk = Entropy.bytes(32)),
            // Another member's, signed by the key derived from theirs.
            "another member's" to ChatEvents.v2(room, other, main, id),
            // Derived for another message id than the body's.
            "misattributed" to ChatEvents.v2(room, me, main, id, outerSk = ChatEvents.outerKey(me.deviceSecretKey, main.id, "ef".repeat(16))),
            // Sealed under an epoch this room is not on and has not left.
            "unknown epoch" to ChatEvents.v2(room, me, ChatEvents.Stream(Fixtures.room(9).roomId, Fixtures.room(9).roomKey), id),
        )
        for ((what, event) in notMine) {
            val chat = prepared(event)
            assertFailsWith<IllegalArgumentException>(what) { live.sendPreparedChatDurable(chat) }
            assertFailsWith<IllegalArgumentException>(what) { live.sendPreparedChatConfirmed(chat) { true } }
        }
        assertTrue(relay.published.none { it.kind == KIND_CHAT })
        assertTrue(outbox(store).items().isEmpty())
        live.leave()
    }

    @Test fun `a flagged row the session cannot show to be its own is never offered`() = runTest {
        val relay = FakeRelay(); val store = Store()
        val journal = outbox(store)
        // As if a stranger's event had been written into the journal with the flag.
        val forged = ChatEvents.v2(room, me, main, id, outerSk = Entropy.bytes(32))
        journal.retain(main.id, forged, text = "hello", messageId = id, outer = true)
        val live = TestScopeSession(relay = relay, outbox = journal, scope = backgroundScope)
        live.join(); advanceTimeBy(1_000); runCurrent()
        live.retryPendingChat()
        assertTrue(relay.published.none { it.kind == KIND_CHAT })
        assertEquals(PendingChatState.MOVED, journal.items().single().state)
        live.leave()
    }

    @Test fun `the background flush offers a flagged envelope 2 row as it is, holding no keys`() = runTest {
        val store = Store(); val journal = outbox(store)
        val v2 = ChatEvents.v2(room, me, main, id)
        journal.retain(main.id, v2, text = "hello", messageId = id, outer = true)
        val offered = mutableListOf<NostrEvent>()
        val transport = object : RoomTransport by FakeRelay().transport() {
            override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean { offered += event; return true }
        }
        assertEquals(FlushOutcome.SENT, flushPending(outbox(store), main.id, transport))
        assertEquals(listOf(v2), offered)
        assertTrue(outbox(store).items().isEmpty())
    }

    @Test fun `an envelope 1 message is sent as before`() = runTest {
        val relay = FakeRelay(); val store = Store()
        val live = TestScopeSession(relay = relay, outbox = outbox(store), scope = backgroundScope)
        live.join(); advanceTimeBy(1_000); runCurrent()
        val chat = requireNotNull(live.prepareChatForSend("Plain old envelope 1"))
        assertFalse(chat.pending.outer)
        assertEquals(me.devicePubkey, chat.pending.event.pubkey)
        assertTrue(live.sendPreparedChatDurable(chat))
        assertNotNull(relay.published.singleOrNull { it.id == chat.pending.event.id })
        live.leave()
    }
}
