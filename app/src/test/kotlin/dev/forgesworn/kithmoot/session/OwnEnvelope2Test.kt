package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.EpochKeys
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Finding a device's own chat envelope 2 messages by stream: they are signed by outer keys made
 * per message, so no author query names them. The events are built in `ChatEvents` from the
 * primitives; writers are not switched on.
 */
class OwnEnvelope2Test {
    private val room = Fixtures.room()
    private val me = Fixtures.primary(room, 1, 2)
    private val other = Fixtures.primary(room, 40, 41)
    private val epoch = deriveEpoch(RoomEpoch(1, ByteArray(32) { (90 + it).toByte() }))
    private val main = ChatEvents.Stream(room.roomId, room.roomKey)
    private val control = ChatEvents.Stream(room.roomId, room.roomKey, CONTROL_CHANNEL)
    private val epochMain = ChatEvents.Stream(epoch.id, epoch.key)

    private fun streams(vararg s: ChatEvents.Stream) = s.map { ChatStream(it.id, it.key) }

    private fun mine(events: List<dev.forgesworn.kithmoot.protocol.NostrEvent>, vararg s: ChatEvents.Stream, deviceKey: ByteArray = me.deviceSecretKey) =
        ownEnvelope2Events(events, streams(*s), deviceKey)

    @Test
    fun `a message this device wrote in envelope 2 is found with the outer key that signed it`() {
        val event = ChatEvents.v2(room, me, main, "m1")
        val found = mine(listOf(event), main).single()
        assertEquals(event, found.event)
        assertNotEquals(me.devicePubkey, event.pubkey, "the device did not sign it")
        assertContentEquals(ChatEvents.outerKey(me.deviceSecretKey, main.id, "m1"), found.secretKey)
        assertEquals(event.pubkey, Schnorr.publicKeyHex(found.secretKey))
    }

    @Test
    fun `messages in the main chat, a channel and an epoch are each found under their own outer key, in order`() {
        val events = listOf(ChatEvents.v2(room, me, main, "a"), ChatEvents.v2(room, me, control, "b"), ChatEvents.v2(room, me, epochMain, "c"))
        val found = mine(events, main, control, epochMain)
        assertEquals(events, found.map { it.event })
        assertEquals(3, found.map { it.event.pubkey }.toSet().size, "one outer key each")
        // A stream given in upper case is the same stream.
        val shouted = ownEnvelope2Events(events, listOf(ChatStream(main.id.uppercase(), main.key)), me.deviceSecretKey)
        assertEquals(listOf(events[0]), shouted.map { it.event })
    }

    @Test
    fun `an envelope 1 line, another member's, a forgery and a stream not given are not found`() {
        val own = ChatEvents.v2(room, me, main, "mine")
        val v1 = ChatEvents.v1(room, me, main, "old")
        val theirs = ChatEvents.v2(room, other, main, "theirs")
        // Names this device and carries its signature, but the event was signed by a key nobody can recompute.
        val forged = ChatEvents.v2(room, me, main, "forged", outerSk = Entropy.bytes(32))
        // Signed by the key derived for another message id than the body's.
        val misattributed = ChatEvents.v2(room, me, main, "second", outerSk = ChatEvents.outerKey(me.deviceSecretKey, main.id, "first"))
        // Signed by the right key for the wrong stream: derived for the control channel, sent to the main chat.
        val wrongStream = ChatEvents.v2(room, me, main, "third", outerSk = ChatEvents.outerKey(me.deviceSecretKey, control.id, "third"))
        val elsewhere = ChatEvents.v2(room, me, epochMain, "elsewhere")
        val all = listOf(v1, theirs, forged, misattributed, wrongStream, elsewhere, own)
        assertEquals(listOf(own), mine(all, main).map { it.event })
        // Not given the epoch's stream, the message in it is not found; given it, it is.
        assertEquals(listOf(elsewhere, own), mine(all, main, epochMain).map { it.event })
        // And the envelope 1 line is still the author query's, never this one's.
        assertEquals(me.devicePubkey, v1.pubkey)
    }

    @Test
    fun `a message is found for the device its body names, and for no other`() {
        val theirs = ChatEvents.v2(room, other, main, "theirs")
        assertEquals(emptyList(), mine(listOf(theirs), main).map { it.event })
        assertEquals(listOf(theirs), mine(listOf(theirs), main, deviceKey = other.deviceSecretKey).map { it.event })
        assertEquals(emptyList(), mine(listOf(ChatEvents.v2(room, me, main, "mine")), main, deviceKey = other.deviceSecretKey).map { it.event })
    }

    @Test
    fun `rubbish is skipped without throwing, and what follows it is still found`() {
        val own = ChatEvents.v2(room, me, main, "mine")
        val outer = ChatEvents.outerKey(me.deviceSecretKey, main.id, "extra")
        fun wrapped(envelope: JsonObject) = Events.sign(outer, KIND_CHAT, 100, listOf(listOf("d", main.id)), Nip44.encrypt(envelope.toString(), main.key))
        val good = Json.parseToJsonElement(Nip44.decrypt(ChatEvents.v2(room, me, main, "extra", outerSk = outer).content, main.key)).jsonObject
        val rubbish = listOf(
            Events.sign(outer, KIND_CHAT, 100, listOf(listOf("d", main.id)), "not a NIP-44 payload"),
            Events.sign(outer, KIND_CHAT, 100, listOf(listOf("d", main.id)), Nip44.encrypt("[]", main.key)),
            Events.sign(outer, KIND_CHAT, 100, emptyList(), Nip44.encrypt(good.toString(), main.key)),
            Events.sign(outer, 1, 100, listOf(listOf("d", main.id)), Nip44.encrypt(good.toString(), main.key)),
            wrapped(JsonObject(good + ("extra" to JsonPrimitive(1)))),
            wrapped(JsonObject(good + ("v" to JsonPrimitive(3)))),
            wrapped(JsonObject(good + ("body" to JsonPrimitive("not json")))),
            wrapped(JsonObject(good + ("body" to JsonPrimitive("{}")))),
            wrapped(JsonObject(good + ("body" to JsonPrimitive("{\"id\":1,\"device\":\"${me.devicePubkey}\"}")))),
        )
        assertEquals(listOf(own), mine(rubbish + own, main).map { it.event })
        // The same message, well formed, is found: the shapes above are what stopped it.
        assertEquals(1, mine(listOf(wrapped(good)), main).size)
    }

    @Test
    fun `the outer key derives as the reference does and is refused outside the curve order`() {
        val sk = chatOuterSecretKey(me.deviceSecretKey, main.id.uppercase(), "m1")
        assertEquals(ChatEvents.outerKey(me.deviceSecretKey, main.id, "m1").toHex(), sk.toHex())
        assertNotEquals(sk.toHex(), chatOuterSecretKey(me.deviceSecretKey, main.id, "m2").toHex())
        assertNotEquals(sk.toHex(), chatOuterSecretKey(me.deviceSecretKey, control.id, "m1").toHex())

        val n = "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141"
        for (bad in listOf("00".repeat(32), n, "ff".repeat(32))) {
            val key = bad.hexToBytes()
            assertFailsWith<IllegalArgumentException>(bad) { requireChatSecretKey(key) }
            assertTrue(key.all { it == 0.toByte() }, "a refused key is zeroed")
        }
        for (good in listOf("00".repeat(31) + "01", n.dropLast(1) + "0")) {
            val key = good.hexToBytes()
            assertContentEquals(good.hexToBytes(), requireChatSecretKey(key))
        }
    }

    @Test
    fun `a room's streams are the main chat and each channel under each root, once each`() {
        val roots = listOf(EpochKeys(0, room.roomId, room.roomKey), epoch)
        val streams = chatStreamsOf(roots)
        assertEquals(6, streams.size)
        for (root in roots) for (channel in listOf(null, "control", "assignments")) {
            val want = deriveChatChannel(root.id, root.key, channel)
            val got = streams.single { it.d == want.id }
            assertContentEquals(want.key, got.key)
        }
        assertEquals(6, chatStreamsOf(roots + roots).size, "listing a root twice adds nothing")
        assertEquals(listOf(room.roomId), chatStreamsOf(roots.take(1), listOf(null)).map { it.d })
        // A stream keeps its own copy of the key, so wiping it leaves the room's alone.
        val before = room.roomKey.toHex()
        streams.forEach { it.wipe() }
        assertEquals(before, room.roomKey.toHex())
        assertTrue(streams.all { s -> s.key.all { it == 0.toByte() } })
    }
}
