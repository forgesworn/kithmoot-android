package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.protocol.PublicRelayWatermark
import dev.forgesworn.kithmoot.storage.RoomStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PublicCallRelayStoreTest {
    private val key = "a".repeat(64)
    private val id = "b".repeat(64)
    private val state = PublicCallRelayState(
        PublicCallRelayLastGood(key, "relay.example.org"),
        listOf(PublicRelayWatermark(key, 1_793_000_000, id), PublicRelayWatermark("c".repeat(64), 1_792_999_000, "d".repeat(64))),
    )
    private fun decode(text: String) = PublicCallRelayStateCodec.decode(text.toByteArray(Charsets.UTF_8))
    private fun root(vararg changes: Pair<String, kotlinx.serialization.json.JsonElement?>): String {
        val fields = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "v" to JsonPrimitive(1), "on" to JsonPrimitive(true),
            "lastGood" to JsonObject(mapOf("key" to JsonPrimitive(key), "host" to JsonPrimitive("relay.example.org"))),
            "watermarks" to JsonArray(listOf(JsonObject(mapOf("key" to JsonPrimitive(key), "createdAt" to JsonPrimitive(5), "id" to JsonPrimitive(id))))),
        )
        for ((name, value) in changes) if (value == null) fields.remove(name) else fields[name] = value
        return JsonObject(fields).toString()
    }

    @Test fun roundTripsTheLastGoodRelayAndTheWatermarksWithTheirIds() {
        assertEquals(state, PublicCallRelayStateCodec.decode(PublicCallRelayStateCodec.encode(state)))
        val empty = PublicCallRelayState()
        assertEquals(empty, PublicCallRelayStateCodec.decode(PublicCallRelayStateCodec.encode(empty)))
        assertNull(PublicCallRelayStateCodec.decode(PublicCallRelayStateCodec.encode(empty))?.lastGood)
    }

    @Test fun theFileHoldsTheSwitchAndNothingSecret() {
        val text = PublicCallRelayStateCodec.encode(state).toString(Charsets.UTF_8)
        val parsed = Json.parseToJsonElement(text).jsonObject
        assertEquals(setOf("v", "on", "lastGood", "watermarks"), parsed.keys)
        assertEquals("true", parsed.getValue("on").jsonPrimitive.content)
        assertEquals(setOf("key", "createdAt", "id"), parsed.getValue("watermarks").jsonArray[0].jsonObject.keys)
        assertEquals("1", parsed.getValue("v").jsonPrimitive.content)
    }

    @Test fun onlyAVersionOneFileWithTheSwitchOnIsRead() {
        assertNotNull(decode(root()))
        assertNull(decode(root("v" to JsonPrimitive(2))))
        assertNull(decode(root("v" to JsonPrimitive("1"))))
        assertNull(decode(root("v" to null)))
        assertNull(decode(root("on" to JsonPrimitive(false))))
        assertNull(decode(root("on" to JsonPrimitive("true"))))
        assertNull(decode(root("on" to null)))
        assertNull(decode("[]"))
        assertNull(decode("not json"))
        assertNull(decode(""))
        assertNull(PublicCallRelayStateCodec.decode(ByteArray(PublicCallRelayStateCodec.MAX_BYTES + 1) { ' '.code.toByte() }))
        assertNull(PublicCallRelayStateCodec.decode(byteArrayOf(0xC3.toByte(), 0x28)))
    }

    @Test fun aLastGoodRelayThatDoesNotValidateIsDroppedNotRepaired() {
        fun good(k: String, h: String) = JsonObject(mapOf("key" to JsonPrimitive(k), "host" to JsonPrimitive(h)))
        assertEquals(PublicCallRelayLastGood(key, "relay.example.org"), decode(root())?.lastGood)
        for (bad in listOf(good("short", "relay.example.org"), good(key.uppercase(), "relay.example.org"), good(key, "relay.local"),
            good(key, "relay.example"), good(key, "10.0.0.1"), good(key, "single"), good(key, ""), JsonObject(mapOf("key" to JsonPrimitive(key))),
            JsonObject(mapOf("key" to JsonPrimitive(5), "host" to JsonPrimitive("relay.example.org"))))) {
            val read = decode(root("lastGood" to bad))
            assertNotNull(read)
            assertNull(bad.toString(), read?.lastGood)
            assertEquals(1, read?.watermarks?.size)
        }
        assertNull(decode(root("lastGood" to JsonPrimitive("x")))?.lastGood)
        assertNull(decode(root("lastGood" to null))?.lastGood)
    }

    @Test fun watermarksThatDoNotValidateAreDroppedAndTheRestKept() {
        fun mark(k: Any, created: Any, i: Any) = JsonObject(mapOf(
            "key" to if (k is String) JsonPrimitive(k) else JsonPrimitive(k as Number),
            "createdAt" to if (created is String) JsonPrimitive(created) else JsonPrimitive(created as Number),
            "id" to if (i is String) JsonPrimitive(i) else JsonPrimitive(i as Number),
        ))
        val kept = mark(key, 7, id)
        val read = decode(root("watermarks" to JsonArray(listOf(
            mark("short", 7, id), mark(key, 7, "short"), mark(key, "7", id), mark(key, -1, id), mark(key, 1.5, id), mark(key.uppercase(), 7, id),
            mark(5, 7, id), JsonPrimitive("x"), JsonObject(mapOf("key" to JsonPrimitive(key))), kept,
        ))))
        assertEquals(listOf(PublicRelayWatermark(key, 7, id)), read?.watermarks)
        assertEquals(emptyList<PublicRelayWatermark>(), decode(root("watermarks" to null))?.watermarks)
        assertEquals(emptyList<PublicRelayWatermark>(), decode(root("watermarks" to JsonPrimitive("x")))?.watermarks)
    }

    @Test fun atMost1024WatermarksAreKeptAndTheFullFileFits() {
        val marks = (0 until 1500).map { n ->
            PublicRelayWatermark(n.toString(16).padStart(64, '0'), 1_793_000_000L + n, n.toString(16).padStart(64, 'f'))
        }
        val bytes = PublicCallRelayStateCodec.encode(PublicCallRelayState(PublicCallRelayLastGood(key, "relay.example.org"), marks))
        assertTrue("${bytes.size}", bytes.size < PublicCallRelayStateCodec.MAX_BYTES)
        assertEquals(1024, PublicCallRelayStateCodec.decode(bytes)?.watermarks?.size)
        // A hand-made file with more is cut at 1024 too.
        val many = JsonArray(marks.map { JsonObject(mapOf("key" to JsonPrimitive(it.key), "createdAt" to JsonPrimitive(it.createdAt), "id" to JsonPrimitive(it.id))) })
        assertEquals(1024, decode(root("watermarks" to many))?.watermarks?.size)
    }

    private class Memory(var bytes: ByteArray? = null) : RoomStorage {
        var resets = 0
        var failRead = false
        var failReset = false
        override fun read(): ByteArray? = if (failRead) throw IOException("unavailable") else bytes
        override fun write(value: ByteArray) { bytes = value }
        override fun reset() { if (failReset) throw IOException("could not delete"); resets++; bytes = null }
    }

    @Test fun theStoreIsOffUntilItIsWrittenAndSwitchingOffClearsTheFile() {
        val memory = Memory()
        val store = PublicCallRelayStore(memory)
        assertNull(store.read())
        store.write(PublicCallRelayState())
        assertEquals(PublicCallRelayState(), store.read())
        store.write(state)
        assertEquals(state, store.read())
        store.clear()
        assertNull(memory.bytes)
        assertEquals(1, memory.resets)
        assertNull(store.read())
    }

    @Test fun anUnreadableOrForeignFileReadsAsOffAndAFailedClearIsNotHidden() {
        val memory = Memory(byteArrayOf(1, 2, 3))
        val store = PublicCallRelayStore(memory)
        assertNull(store.read())
        memory.bytes = PublicCallRelayStateCodec.encode(state)
        assertEquals(state, store.read())
        memory.failRead = true
        assertNull(store.read())
        memory.failReset = true
        var thrown = false
        try { store.clear() } catch (_: IOException) { thrown = true }
        assertTrue(thrown)
    }

    @Test fun theFileIsItsOwnAndNotTheHandEnteredRelaysFile() {
        assertEquals("kithmoot.public-call-relays.v1", PublicCallRelayStore.FILE)
        assertFalse(PublicCallRelayStore.FILE == "kithmoot.call-relay.v1")
    }
}
