package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Room drops against a wrap and a filler the TypeScript library produced
 * (`room-drop-web.json`, a copy, never an edit): this implementation opens
 * the reference's wrap, and its own wraps and fillers are the same size as
 * the reference's for the same bucket.
 */
class RoomDropTest {

    private val fixture: JsonObject by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/room-drop-web.json"))
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }
    private val now get() = fixture.getValue("now").jsonPrimitive.long
    private val bucket get() = fixture.getValue("bucket").jsonPrimitive.content.toInt()

    @Test
    fun theReferencesWrapOpensToItsInnerEventUnderTheDerivedKey() {
        val wrap = NostrEvent.fromJson(fixture.getValue("wrap"))
        val inner = NostrEvent.fromJson(fixture.getValue("inner"))
        val ikm = DeadDrop.roomIkm(fixture.getValue("roomKeyHex").jsonPrimitive.content.hexToBytes())
        val key = DeadDrop.deriveDropKey(ikm, fixture.getValue("epochIndex").jsonPrimitive.long, fixture.getValue("member").jsonPrimitive.content, fixture.getValue("counter").jsonPrimitive.content.toInt())
        assertEquals(fixture.getValue("dropPubXOnly").jsonPrimitive.content, key.publicKey)
        assertEquals(fixture.getValue("dropPrivHex").jsonPrimitive.content, key.privateKey.toHex())
        assertEquals(listOf(listOf("p", key.publicKey)), wrap.tags)
        val opened = RoomDrops.openRoomDrop(wrap, key.privateKey)
        assertNotNull(opened)
        assertEquals(inner.id, opened!!.id)
        assertEquals(inner.content, opened.content)
        // The wrong key, or a key for the wrong counter, opens nothing and throws nothing.
        assertNull(RoomDrops.openRoomDrop(wrap, DeadDrop.deriveDropKey(ikm, fixture.getValue("epochIndex").jsonPrimitive.long, fixture.getValue("member").jsonPrimitive.content, 4).privateKey))
        assertNull(RoomDrops.openRoomDrop(inner, key.privateKey))
    }

    @Test
    fun aWrapAndAFillerMadeHereAreTheSizeOfTheReferencesAndSayNothingElse() {
        val wrap = NostrEvent.fromJson(fixture.getValue("wrap"))
        val inner = NostrEvent.fromJson(fixture.getValue("inner"))
        val key = DeadDrop.deriveDropKey(DeadDrop.roomIkm(fixture.getValue("roomKeyHex").jsonPrimitive.content.hexToBytes()), fixture.getValue("epochIndex").jsonPrimitive.long, fixture.getValue("member").jsonPrimitive.content, 5)
        val mine = RoomDrops.createRoomDrop(inner, key.publicKey, bucket, now)
        val filler = RoomDrops.createRoomFiller(1460, bucket, now)
        assertEquals(wrap.content.length, mine.content.length)
        assertEquals(wrap.content.length, filler.content.length)
        assertEquals(listOf("p"), mine.tags.map { it[0] })
        assertEquals(listOf("p"), filler.tags.map { it[0] })
        assertTrue(Events.verify(mine))
        assertTrue(Events.verify(filler))
        assertTrue(mine.pubkey != inner.pubkey)
        assertTrue(mine.createdAt <= now && mine.createdAt > now - RoomDrops.CREATED_AT_JITTER)
        assertEquals(inner.id, RoomDrops.openRoomDrop(mine, key.privateKey)!!.id)
        // A filler opens under nobody's key in the room.
        assertNull(RoomDrops.openRoomDrop(filler, key.privateKey))
        // The plaintext is exactly the bucket, whatever the inner says.
        assertEquals(bucket, RoomDrops.plaintext(inner, bucket).toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun anEventTooBigForTheBucketIsRefusedBeforeAnythingIsWrapped() {
        val inner = NostrEvent.fromJson(fixture.getValue("inner"))
        var thrown = false
        try { RoomDrops.plaintext(inner, 100) } catch (_: RoomDrops.RumorTooLarge) { thrown = true }
        assertTrue(thrown)
    }
}
