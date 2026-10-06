package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.EpochKeys
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.decodeRekeyEvent
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.epochsInWindow
import dev.forgesworn.kithmoot.service.backgroundChatFilter
import dev.forgesworn.kithmoot.service.decodeBackgroundChat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `chatHistory` vectors against Android's own code: the window of left epochs a chat log
 * reads (`epochsInWindow`, what `pastEpochsFor` applies), each epoch's main-chat stream, the
 * epochs a background chat filter asks for (`backgroundChatFilter`), and the messages either
 * side of a scheduled rekey (`decodeRekeyEvent`, `decodeBackgroundChat`).
 *
 * Not applicable here: the vectors' `filters` layout. The web's log folds the older epochs into
 * one filter with several `#d` values, at most six filters, each with its own `limit`, because
 * public relays cap the filters a request may carry. Android has no chat log reading history
 * through paged REQs: `backgroundChatFilter` is a single filter with every `#d`, so only the
 * epochs asked for (their streams, current first, then the rest newest first) are compared, not
 * how they are grouped.
 */
class ChatHistoryVectorsTest {

    private val group: List<JsonObject> by lazy {
        val file = listOf("../protocol/src/test/resources/kithmoot-vectors.json", "protocol/src/test/resources/kithmoot-vectors.json")
            .map(::File).first { it.exists() }
        Json.parseToJsonElement(file.readText()).jsonObject.getValue("groups").jsonObject.getValue("chatHistory").jsonArray.map { it.jsonObject }
    }
    private fun vector(name: String) = group.single { it.text("name") == name }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.child(key: String) = getValue(key).jsonObject
    private fun JsonObject.list(key: String): JsonArray = getValue(key).jsonArray
    private fun JsonArray.ints() = map { it.jsonPrimitive.content.toInt() }
    private fun JsonArray.strings() = map { it.jsonPrimitive.content }

    private class Left(val epoch: Int, val leftAt: Long, val secret: String?)

    private fun leftOf(input: JsonObject) = input.list("left").map { it.jsonObject }
        .map { Left(it.text("epoch").toInt(), it.long("leftAt"), it["secretHex"]?.jsonPrimitive?.content) }

    private fun inWindow(input: JsonObject, now: Long) = epochsInWindow(leftOf(input), now, { it.epoch }, { it.leftAt })

    /** The main chat's stream in an epoch: its id, or the room id in epoch 0. */
    private fun stream(roomId: String, left: Left) = if (left.epoch == 0) roomId else deriveEpoch(RoomEpoch(left.epoch, left.secret!!.hexToBytes())).id

    private fun flat(filters: JsonArray) = filters.flatMap { it.jsonArray.strings() }

    @Test
    fun `the group is five vectors, one of them a negative`() {
        assertEquals(
            listOf("streams-with-removals", "streams-weekly", "streams-at-the-cap", "across-a-scheduled-rekey", "left-before-the-window"),
            group.map { it.text("name") },
        )
        assertEquals(listOf("negative"), group.filter { it.text("kind") == "negative" }.map { it.text("kind") })
    }

    @Test
    fun `the epochs read and their streams are the ones the reference reads`() {
        for (name in listOf("streams-with-removals", "streams-weekly", "streams-at-the-cap")) {
            val input = vector(name).child("input")
            val output = vector(name).child("output")
            val roomId = input.text("roomId")
            val kept = inWindow(input, input.long("now"))
            assertEquals(output.list("kept").ints(), kept.map { it.epoch }, "$name kept")

            val current = input.child("current")
            val currentKeys = deriveEpoch(RoomEpoch(current.text("epoch").toInt(), current.text("secretHex").hexToBytes()))
            val streams = output.child("streams")
            assertEquals(streams.getValue(currentKeys.epoch.toString()).jsonPrimitive.content, currentKeys.id, "$name current stream")
            for (left in leftOf(input)) assertEquals(streams.text(left.epoch.toString()), stream(roomId, left), "$name stream of epoch ${left.epoch}")

            // The epochs a background filter asks for: the current stream, then the kept ones
            // newest first, which is the order the vector lists its filters in.
            val filter = backgroundChatFilter(currentKeys.id, cursor = 0, now = input.long("now"), pastIds = kept.map { stream(roomId, it) })
            assertEquals(flat(output.list("filters")).toSet(), filter.tags.getValue("#d").toSet(), "$name asked for")
            assertEquals(flat(output.list("filters")), filter.tags.getValue("#d"), "$name asked for in order")
        }
    }

    private fun scheduledRekey(name: String): Pair<JsonObject, JsonObject> = vector(name).child("input") to vector(name).child("output")

    /** The room as a member in epoch 1 sees it once the rekey arrives, and what it reads of the log. */
    private fun readAcrossRekey(name: String) {
        val (input, output) = scheduledRekey(name)
        val roomId = input.text("roomId")
        val now = input.long("now")
        val epoch1 = input.child("epoch1")
        val one = deriveEpoch(RoomEpoch(epoch1.text("epoch").toInt(), epoch1.text("secretHex").hexToBytes()))
        val rekey = input.child("rekey")

        val notice = assertNotNull(decodeRekeyEvent(NostrEvent.fromJson(rekey.child("event")), roomId, input.text("authority"), one, input.text("deviceSkHex").hexToBytes()), "$name rekey decodes")
        output["notice"]?.let { want ->
            val w = want as JsonObject
            assertEquals(w.text("epoch").toInt(), notice.epoch)
            assertEquals(w.list("removed").strings(), notice.removed)
            assertEquals(w.getValue("closed").jsonPrimitive.content.toBoolean(), notice.closed)
            assertEquals(w.getValue("scheduled").jsonPrimitive.content.toBoolean(), notice.scheduled, "a scheduled turn is marked, so a client lets it pass without a word")
            assertEquals(w.text("secretHex"), notice.secret!!.joinToString("") { "%02x".format(it) })
            assertEquals(w.long("at"), notice.at)
        }
        val two = deriveEpoch(RoomEpoch(notice.epoch, notice.secret!!))

        // Left epoch 1 at the rekey, as the vector's `left` records it.
        val left = leftOf(input)
        val kept = epochsInWindow(left, now, { it.epoch }, { it.leftAt })
        val past = kept.map { PastEpoch(deriveEpoch(RoomEpoch(it.epoch, it.secret!!.hexToBytes())), it.leftAt) }
        if (name == "across-a-scheduled-rekey") assertEquals(notice.at, left.single().leftAt, "left when the rekey was signed")

        val filter = backgroundChatFilter(two.id, cursor = 0, now = now, pastIds = past.map { it.keys.id })
        assertEquals(flat(output.list("filters")), filter.tags.getValue("#d"), "$name asked for")

        val read = input.list("messages").map { it.jsonObject }
            .mapNotNull { decodeBackgroundChat(NostrEvent.fromJson(it.child("event")), two, past, emptySet(), now, null, roomId) }
            .sortedBy { it.sentAt }
            .map { it.body }
        assertEquals(output.list("read").strings(), read, "$name read")
    }

    @Test
    fun `messages from either side of a scheduled rekey are both read, in order`() {
        readAcrossRekey("across-a-scheduled-rekey")
        assertEquals(2, vector("across-a-scheduled-rekey").child("output").list("read").size)
    }

    @Test
    fun `an epoch left before the window is not asked for and what was said in it is not read`() {
        readAcrossRekey("left-before-the-window")
        val (input, output) = scheduledRekey("left-before-the-window")
        assertTrue(inWindow(input, input.long("now")).isEmpty())
        assertEquals(listOf("Said in epoch 2."), output.list("read").strings())
    }
}
