package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * KithMoot's room board vectors (`vectors/room-board-v1.json`, copied here
 * verbatim): the board's stream under each epoch, and every event this app's
 * chat reader must take as a board part or refuse. The board's own merging,
 * splitting and starting state belong to the page that runs the board, not to
 * this app, so `genesis`, `merge` and `blobs` are not run here. A decoded part
 * is re-encoded and compared as JSON text, so an unmodelled field cannot pass.
 */
class RoomBoardVectorsTest {

    private val vectors: JsonObject by lazy {
        val file = listOf("src/test/resources/room-board-v1.json", "app/src/test/resources/room-board-v1.json").map(::File).first { it.exists() }
        Json.parseToJsonElement(file.readText()).jsonObject
    }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    /** As KithMoot's own vector test reads it: the board's channel unless `channel` is null, under `epoch` when one is given. */
    private fun decode(vector: JsonObject): ChatMessage? {
        val d = vector.getValue("decode").jsonObject
        val epoch = d["epoch"]?.jsonObject
        val channel = if (d.containsKey("channel") && d["channel"] is JsonNull) null else ROOM_BOARD_CHANNEL
        return decodeChatEvent(NostrEvent.fromJson(vector.getValue("event").jsonObject),
            epoch?.text("id") ?: d.text("roomId"), (epoch?.text("key") ?: d.text("roomKey")).hexToBytes(),
            d.getValue("now").jsonPrimitive.long, channel = channel, credentialRoomId = d.text("roomId"))
    }

    @Test fun `the board's channel and its stream under each epoch`() {
        assertEquals(ROOM_BOARD_CHANNEL, vectors.text("channel"))
        val limits = vectors.getValue("limits").jsonObject
        assertEquals(MAX_BOARD_PART_BYTES.toLong(), limits.getValue("partBytes").jsonPrimitive.long)
        assertEquals(MAX_BOARD_PARTS.toLong(), limits.getValue("maxParts").jsonPrimitive.long)
        val inputs = vectors.getValue("inputs").jsonObject
        val epoch = inputs.getValue("epoch").jsonObject
        val streams = vectors.getValue("streams").jsonArray.map { it.jsonObject }
        val first = deriveChatChannel(inputs.text("roomId"), inputs.text("roomKey").hexToBytes(), ROOM_BOARD_CHANNEL)
        val later = deriveChatChannel(epoch.text("id"), epoch.text("key").hexToBytes(), ROOM_BOARD_CHANNEL)
        assertEquals(listOf("epoch-0" to (first.id to first.key.toHex()), "later-epoch" to (later.id to later.key.toHex())),
            streams.map { it.text("name") to (it.text("d") to it.text("key")) })
    }

    @Test fun `accepts every board part the reference accepts, exactly as it reads it`() {
        val valid = vectors.getValue("valid").jsonArray.map { it.jsonObject }
        assertTrue(valid.size >= 5)
        for (vector in valid) {
            val name = vector.text("name")
            val message = assertNotNull(decode(vector), "$name must decode")
            val expected = vector.getValue("expected").jsonObject
            assertEquals(expected.text("participant"), message.participant, name)
            assertEquals(expected.text("device"), message.device, name)
            assertEquals(expected.getValue("sentAt").jsonPrimitive.long, message.sentAt, name)
            assertEquals("Board update", message.body, name)
            assertEquals(expected.getValue("board").jsonObject.toString(), assertNotNull(message.board, name).toJson().toString(),
                "$name re-encodes exactly as the reference reads it")
        }
    }

    @Test fun `refuses every event the reference refuses`() {
        val refused = vectors.getValue("refused").jsonArray.map { it.jsonObject }
        assertTrue(refused.size >= 20)
        for (vector in refused) assertNull(decode(vector), "${vector.text("name")} must be refused")
    }

    @Test fun `a board part this app writes is one the reference's reader takes, in the board channel only`() {
        val inputs = vectors.getValue("inputs").jsonObject
        val roomId = inputs.text("roomId")
        val roomKey = inputs.text("roomKey").hexToBytes()
        val credential = NostrEvent.fromJson(inputs.getValue("credential").jsonObject)
        val now = inputs.getValue("now").jsonPrimitive.long
        val part = assertNotNull(vectors.getValue("valid").jsonArray.first().jsonObject.getValue("expected").jsonObject["board"]?.let(::parseBoardPart))
        val event = encodeChatEvent("Board update", credential.pubkey, credential, roomId, roomKey,
            inputs.text("deviceSecretKey").hexToBytes(), now, channel = ROOM_BOARD_CHANNEL, board = part)
        val read = assertNotNull(decodeChatEvent(event, roomId, roomKey, now, channel = ROOM_BOARD_CHANNEL))
        assertEquals(part, read.board)
        // Not conversation, and not a statement any other channel carries.
        assertNull(decodeChatEvent(event, roomId, roomKey, now))
        assertNull(decodeChatEvent(event, roomId, roomKey, now, channel = CONTROL_CHANNEL))
        assertFailsWith<IllegalArgumentException> {
            encodeChatEvent("Board update", credential.pubkey, credential, roomId, roomKey, inputs.text("deviceSecretKey").hexToBytes(), now,
                channel = CONTROL_CHANNEL, board = part)
        }
        assertFailsWith<IllegalArgumentException> {
            encodeChatEvent("Board update", credential.pubkey, credential, roomId, roomKey, inputs.text("deviceSecretKey").hexToBytes(), now,
                channel = ROOM_BOARD_CHANNEL, board = part, mentions = listOf(credential.pubkey))
        }
    }

    @Test fun `a part is taken in its one honest shape only`() {
        val good = buildJsonObject { put("v", 1); put("kind", "update"); put("blob", "0".repeat(32)); put("index", 0); put("count", 1); put("data", "AAAA") }
        assertNotNull(parseBoardPart(good))
        fun with(key: String, value: kotlinx.serialization.json.JsonElement) = JsonObject(good + (key to value))
        assertNull(parseBoardPart(with("v", JsonPrimitive("1"))))
        assertNull(parseBoardPart(with("v", JsonPrimitive(true))))
        assertNull(parseBoardPart(with("count", JsonPrimitive(1.5))))
        assertNull(parseBoardPart(with("count", JsonPrimitive(97))))
        assertNull(parseBoardPart(with("index", JsonPrimitive(1))))
        assertNull(parseBoardPart(with("data", JsonPrimitive("A".repeat(30_044)))))
        assertNull(parseBoardPart(with("extra", JsonPrimitive(0))))
        assertNull(parseBoardPart(JsonNull))
        // A JavaScript reader takes 1.0 as 1, so this does too.
        assertNotNull(parseBoardPart(with("v", JsonPrimitive(1.0))))
    }
}
