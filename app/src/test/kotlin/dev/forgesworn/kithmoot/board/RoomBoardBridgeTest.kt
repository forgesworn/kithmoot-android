package dev.forgesworn.kithmoot.board

import dev.forgesworn.kithmoot.protocol.EpochKeys
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.session.BoardPart
import dev.forgesworn.kithmoot.session.Fixtures
import dev.forgesworn.kithmoot.session.KIND_CHAT
import dev.forgesworn.kithmoot.session.ROOM_BOARD_CHANNEL
import dev.forgesworn.kithmoot.session.decodeChatEvent
import dev.forgesworn.kithmoot.session.deriveChatChannel
import dev.forgesworn.kithmoot.session.encodeChatEvent
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The app's half of the room board's port, with this test as the page: it reads the board's stream and hands
 * over parts opened as chat is, seals and publishes only board parts, keeps the page's copy, and never sends the
 * page a key.
 */
class RoomBoardBridgeTest {
    private val room = Fixtures.room()
    private val ada = Fixtures.primary(room, 1, 2)
    private val bob = Fixtures.primary(room, 3, 4)
    private val relay = FakeRelay()
    private val executor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val heard = CopyOnWriteArrayList<JsonObject>()

    @AfterTest fun stop() { scope.cancel(); executor.shutdownNow() }

    /** [relay] as a storing relay: what it holds, then that it has sent it all, then what comes live. */
    private fun storing(): RoomTransport {
        val base = relay.transport()
        return object : RoomTransport by base {
            // As the pool does: completion in the collector's own order, after every stored event has been taken.
            override fun subscribeReplayed(filters: List<Filter>, onReplayComplete: () -> Unit): Flow<NostrEvent> = flow {
                val d = filters.single().tags.getValue("#d").toSet()
                for (event in relay.published.toList()) if (event.tagValue("d") in d) emit(event)
                onReplayComplete()
                emitAll(base.subscribe(filters))
            }
        }
    }

    private class Memory : RoomBoardCopyStore {
        val records = LinkedHashMap<String, ByteArray>()
        override fun read() = records.map { it.key to it.value } to 0
        override fun add(update: ByteArray) = RoomBoardBridge.recordId(update).also { records[it] = update }
        override fun remove(ids: List<String>) { ids.forEach(records::remove) }
    }

    private fun bridge(copy: RoomBoardCopyStore = Memory(), credential: suspend () -> NostrEvent = { ada.credential },
        offers: RoomBoardBridge.Offers = RoomBoardBridge.Offers(), hooks: RoomBoardBridge.Hooks = RoomBoardBridge.Hooks(),
        epoch: EpochKeys = EpochKeys(0, room.roomId, room.roomKey), transport: RoomTransport = storing()) =
        RoomBoardBridge(room.roomId, ada.participant, ada.deviceSecretKey, credential, transport, copy, scope,
            { raw -> heard += Json.parseToJsonElement(raw).jsonObject }, epoch, offers = offers, hooks = hooks, now = { 200 })

    private fun RoomBoardBridge.say(body: JsonObject) = receive(JsonObject(mapOf("v" to JsonPrimitive(1)) + body).toString())
    private fun RoomBoardBridge.ask(t: String, re: String, vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>) =
        say(JsonObject(mapOf("t" to JsonPrimitive(t), "re" to JsonPrimitive(re)) + fields))
    private fun said(t: String) = heard.filter { it["t"]?.jsonPrimitive?.content == t }
    private fun reply(re: String) = heard.single { it["t"]?.jsonPrimitive?.content == "reply" && it["re"]?.jsonPrimitive?.content == re }

    private fun waitFor(what: String, check: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!check()) kotlinx.coroutines.delay(10) }
        assertTrue(check(), what)
    }

    private val part = BoardPart("update", "0".repeat(32), 0, 1, "AAAA")

    /** A board part a member on the web sent, in [epoch]. */
    private fun webPart(epoch: EpochKeys = EpochKeys(0, room.roomId, room.roomKey), data: String = "AQID") = encodeChatEvent("Board update",
        bob.participant, bob.credential, epoch.id, epoch.key, bob.deviceSecretKey, 150, channel = ROOM_BOARD_CHANNEL,
        credentialRoomId = room.roomId, board = BoardPart("snapshot", "1".repeat(32), 0, 1, data))

    @Test fun `starts the page, then hands it every part in the room's board stream, opened as chat is, and says when it has them all`() {
        relay.publish(webPart())
        // Not the board's: the main chat, and a board part from somebody who is not in the room.
        relay.publish(encodeChatEvent("Hello", bob.participant, bob.credential, room.roomId, room.roomKey, bob.deviceSecretKey, 150))
        val stranger = Fixtures.primary(Fixtures.room(9), 5, 6)
        relay.publish(encodeChatEvent("Board update", stranger.participant, stranger.credential, room.roomId, room.roomKey, stranger.deviceSecretKey, 150,
            channel = ROOM_BOARD_CHANNEL, credentialRoomId = Fixtures.room(9).roomId, board = part))
        val b = bridge(offers = RoomBoardBridge.Offers(invite = true))
        b.members(listOf(ada.participant to "Ada", bob.participant to "Bob"))
        b.say(buildJsonObject { put("t", "ready") })
        waitFor("start") { said("start").isNotEmpty() && said("members").isNotEmpty() }
        val start = said("start").single()
        assertEquals(ada.participant, start.getValue("self").jsonPrimitive.content)
        assertEquals(listOf("true", "false", "false"), listOf("invite", "assign", "files").map { start.getValue(it).jsonPrimitive.content })
        assertEquals(listOf("Ada", "Bob"), said("members").last().getValue("members").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })

        b.say(buildJsonObject { put("t", "listen"); put("gen", 1) })
        waitFor("caught up") { said("caughtUp").isNotEmpty() }
        val parts = said("part")
        assertEquals(1, parts.size)
        assertEquals(1, parts.single().getValue("gen").jsonPrimitive.int)
        assertEquals(bob.devicePubkey, parts.single().getValue("device").jsonPrimitive.content)
        assertEquals(150, parts.single().getValue("sentAt").jsonPrimitive.long)
        assertEquals("1".repeat(32), parts.single().getValue("part").jsonObject.getValue("blob").jsonPrimitive.content)
        assertTrue(heard.indexOf(said("caughtUp").single()) > heard.indexOf(parts.single()))

        // One that arrives live comes too.
        relay.publish(webPart(data = "BAUG"))
        waitFor("a live part") { said("part").size == 2 }
        // And nothing the page is handed carries a key.
        val secrets = listOf(room.roomKey, ada.deviceSecretKey).map { key -> key.joinToString("") { "%02x".format(it) } }
        for (message in heard) for (secret in secrets) assertFalse(message.toString().contains(secret))
    }

    @Test fun `seals a board part as this device in the room's board channel, and sends it only when asked, saying ok once a relay has it`() {
        val b = bridge()
        b.say(buildJsonObject { put("t", "ready") })
        b.say(buildJsonObject { put("t", "listen"); put("gen", 1) })
        b.ask("seal", "r1", "part" to part.toJson())
        waitFor("sealed") { said("reply").any { it["re"]?.jsonPrimitive?.content == "r1" } }
        val sealed = reply("r1")
        assertEquals("true", sealed.getValue("ok").jsonPrimitive.content)
        assertEquals(200, sealed.getValue("createdAt").jsonPrimitive.long)
        assertTrue(relay.published.none { it.id == sealed.getValue("event").jsonPrimitive.content }, "not sent before it is asked for")
        b.ask("publish", "r2", "event" to sealed.getValue("event"))
        waitFor("published") { said("reply").any { it["re"]?.jsonPrimitive?.content == "r2" } }
        assertEquals("true", reply("r2").getValue("ok").jsonPrimitive.content)
        val event = relay.published.single { it.id == sealed.getValue("event").jsonPrimitive.content }
        assertEquals(KIND_CHAT, event.kind)
        assertEquals(deriveChatChannel(room.roomId, room.roomKey, ROOM_BOARD_CHANNEL).id, event.tagValue("d"))
        val read = assertNotNull(decodeChatEvent(event, room.roomId, room.roomKey, 200, channel = ROOM_BOARD_CHANNEL))
        assertEquals(part, read.board)
        assertEquals(ada.participant, read.participant)
        assertEquals("Board update", read.body)
    }

    @Test fun `refuses, in words, anything but a board part, a credential that has run out, and a send no relay takes`() {
        relay.confirmsPublications = false
        var renewable = true
        val b = bridge(credential = { if (renewable) ada.credential else error("cannot renew") })
        b.say(buildJsonObject { put("t", "ready") })
        b.say(buildJsonObject { put("t", "listen"); put("gen", 1) })
        b.ask("seal", "r1", "part" to buildJsonObject { put("text", "Hello") })
        b.ask("seal", "r2", "part" to JsonObject(part.toJson() + ("extra" to JsonPrimitive(1))))
        b.ask("seal", "r3", "part" to part.toJson())
        waitFor("answers") { said("reply").size == 3 }
        assertEquals("That is not a board change", reply("r1").getValue("error").jsonPrimitive.content)
        assertEquals("That is not a board change", reply("r2").getValue("error").jsonPrimitive.content)
        b.ask("publish", "r4", "event" to reply("r3").getValue("event"))
        waitFor("refused send") { said("reply").size == 4 }
        assertEquals("No relay took the board change", reply("r4").getValue("error").jsonPrimitive.content)
        renewable = false
        b.ask("seal", "r5", "part" to part.toJson())
        waitFor("refused seal") { said("reply").size == 5 }
        assertEquals("This device's access to the room ran out, so the board change was not sent", reply("r5").getValue("error").jsonPrimitive.content)
        assertTrue(relay.published.isEmpty())
    }

    @Test fun `answers the page when no relay says anything in time, or renewing access takes too long`() {
        // As the pool does with no relay up: a confirmed send waits for a relay's OK only so long, then times out.
        val base = storing()
        val silent = object : RoomTransport by base {
            override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean = withTimeout(50) { awaitCancellation() }
        }
        var slow = false
        val b = bridge(transport = silent, credential = { if (slow) withTimeout(50) { awaitCancellation() } else ada.credential })
        b.say(buildJsonObject { put("t", "ready") })
        b.say(buildJsonObject { put("t", "listen"); put("gen", 1) })
        b.ask("seal", "r1", "part" to part.toJson())
        waitFor("sealed") { said("reply").any { it["re"]?.jsonPrimitive?.content == "r1" } }
        b.ask("publish", "r2", "event" to reply("r1").getValue("event"))
        // Unanswered, the page would wait for good and say it is still sending, long after the network is back.
        waitFor("refused send") { said("reply").any { it["re"]?.jsonPrimitive?.content == "r2" } }
        assertEquals("No relay took the board change", reply("r2").getValue("error").jsonPrimitive.content)
        slow = true
        b.ask("seal", "r3", "part" to part.toJson())
        waitFor("refused seal") { said("reply").any { it["re"]?.jsonPrimitive?.content == "r3" } }
        assertEquals("This device's access to the room ran out, so the board change was not sent", reply("r3").getValue("error").jsonPrimitive.content)
    }

    @Test fun `forgets what it sealed when the reading it was sealed in ends`() {
        val b = bridge()
        b.say(buildJsonObject { put("t", "ready") })
        b.say(buildJsonObject { put("t", "listen"); put("gen", 1) })
        b.ask("seal", "r1", "part" to part.toJson())
        waitFor("sealed") { said("reply").size == 1 }
        b.say(buildJsonObject { put("t", "unlisten"); put("gen", 1) })
        b.ask("publish", "r2", "event" to reply("r1").getValue("event"))
        waitFor("refused") { said("reply").size == 2 }
        assertEquals("That board change is no longer waiting to be sent", reply("r2").getValue("error").jsonPrimitive.content)
        assertTrue(relay.published.isEmpty())
    }

    @Test fun `follows the room to a new epoch, where the page reads that epoch's stream`() {
        val later = EpochKeys(1, "c1".repeat(32), ByteArray(32) { 3 })
        relay.publish(webPart(later))
        val b = bridge()
        b.say(buildJsonObject { put("t", "ready") })
        b.say(buildJsonObject { put("t", "listen"); put("gen", 1) })
        waitFor("first reading") { said("caughtUp").isNotEmpty() }
        assertTrue(said("part").isEmpty())
        b.rekey(later)
        waitFor("rekey") { said("rekey").isNotEmpty() }
        b.say(buildJsonObject { put("t", "unlisten"); put("gen", 1) })
        b.say(buildJsonObject { put("t", "listen"); put("gen", 2) })
        waitFor("the new epoch's part") { said("part").any { it.getValue("gen").jsonPrimitive.int == 2 } }
        b.ask("seal", "r1", "part" to part.toJson())
        waitFor("sealed") { said("reply").isNotEmpty() }
        b.ask("publish", "r2", "event" to reply("r1").getValue("event"))
        waitFor("published") { said("reply").size == 2 }
        val event = relay.published.last()
        assertEquals(deriveChatChannel(later.id, later.key, ROOM_BOARD_CHANNEL).id, event.tagValue("d"))
        assertNotNull(decodeChatEvent(event, later.id, later.key, 200, channel = ROOM_BOARD_CHANNEL, credentialRoomId = room.roomId)?.board)
    }

    @Test fun `keeps the page's copy in pieces, hands it back, and forgets what the page asks it to`() {
        val copy = Memory()
        val b = bridge(copy)
        b.say(buildJsonObject { put("t", "ready") })
        val update = ByteArray(45_000) { (it % 251).toByte() }
        val pieces = listOf(update.copyOfRange(0, 30_000), update.copyOfRange(30_000, 45_000)).map { Base64.getEncoder().encodeToString(it) }
        pieces.forEachIndexed { index, data -> b.ask("copy.add", "r1", "index" to JsonPrimitive(index), "count" to JsonPrimitive(2), "data" to JsonPrimitive(data)) }
        waitFor("kept") { said("reply").isNotEmpty() }
        val record = reply("r1").getValue("record").jsonPrimitive.content
        assertTrue(copy.records.getValue(record).contentEquals(update))
        b.ask("copy.read", "r2")
        waitFor("read") { said("reply").size == 2 }
        val back = said("copy.record").filter { it["re"]?.jsonPrimitive?.content == "r2" }
        assertEquals(listOf(0, 1), back.map { it.getValue("index").jsonPrimitive.int })
        assertTrue(back.flatMap { Base64.getDecoder().decode(it.getValue("data").jsonPrimitive.content).toList() }.toByteArray().contentEquals(update))
        assertEquals(0, reply("r2").getValue("unread").jsonPrimitive.int)
        b.ask("copy.remove", "r3", "records" to buildJsonArray { add(JsonPrimitive(record)) })
        waitFor("removed") { said("reply").size == 3 }
        assertTrue(copy.records.isEmpty())
    }

    @Test fun `closing lets the page finish with its copy, then answers nothing more`() {
        val copy = Memory()
        val b = bridge(copy)
        b.say(buildJsonObject { put("t", "ready") })
        b.say(buildJsonObject { put("t", "listen"); put("gen", 1) })
        b.close()
        waitFor("asked to close") { said("close").isNotEmpty() }
        b.ask("copy.add", "r1", "index" to JsonPrimitive(0), "count" to JsonPrimitive(1), "data" to JsonPrimitive("AQID"))
        b.ask("seal", "r2", "part" to part.toJson())
        waitFor("kept") { said("reply").isNotEmpty() }
        assertEquals(1, copy.records.size)
        b.say(buildJsonObject { put("t", "closed") })
        runBlocking { withTimeout(5_000) { b.awaitClosed() } }
        b.ask("copy.read", "r3")
        Thread.sleep(100)
        assertEquals(listOf("r1"), said("reply").map { it.getValue("re").jsonPrimitive.content })
    }

    @Test fun `passes the view's buttons to the room as plain text, and only those the room offers`() {
        val discussed = CopyOnWriteArrayList<RoomBoardCard>()
        var invited = 0
        val b = bridge(hooks = RoomBoardBridge.Hooks(discuss = { discussed += it }, invite = { invited++ }))
        b.say(buildJsonObject { put("t", "ready") })
        b.say(buildJsonObject { put("t", "discuss"); put("card", buildJsonObject { put("id", "c1"); put("title", "Book the hall"); put("description", "By Friday") }) })
        b.say(buildJsonObject { put("t", "invite") })
        b.say(buildJsonObject { put("t", "discuss"); put("card", buildJsonObject { put("title", "No id") }) })
        waitFor("discussed") { discussed.isNotEmpty() }
        Thread.sleep(100)
        assertEquals(listOf(RoomBoardCard("c1", "Book the hall", "By Friday")), discussed.toList())
        assertEquals(0, invited)
    }

    private fun RoomBoardBridge.saveIn(re: String, bytes: ByteArray, name: String = "girnel-board.json") {
        ask("save", re, "name" to JsonPrimitive(name), "type" to JsonPrimitive("application/json"), "size" to JsonPrimitive(bytes.size))
        for (offset in bytes.indices step RoomBoardBridge.CHUNK_BYTES)
            ask("save.chunk", re, "offset" to JsonPrimitive(offset),
                "data" to JsonPrimitive(Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, minOf(bytes.size, offset + RoomBoardBridge.CHUNK_BYTES)))))
    }
    private fun answered(re: String) = heard.any { it["t"]?.jsonPrimitive?.content == "reply" && it["re"]?.jsonPrimitive?.content == re }

    @Test fun `hands Export board's file on in acknowledged pieces, under a safe name, and answers once the person has kept it`() {
        val kept = CopyOnWriteArrayList<Pair<String, ByteArray>>()
        val chosen = kotlinx.coroutines.CompletableDeferred<String?>()
        val b = bridge(offers = RoomBoardBridge.Offers(save = true), hooks = RoomBoardBridge.Hooks(save = { name, bytes -> kept += name to bytes; chosen.await() }))
        b.say(buildJsonObject { put("t", "ready") })
        waitFor("started") { said("start").isNotEmpty() }
        assertEquals(true, said("start").single()["save"]?.jsonPrimitive?.content?.toBoolean())
        val bytes = ByteArray(70_000) { (it % 251).toByte() }
        b.saveIn("r1", bytes, name = "../../girnel-board.json")
        waitFor("handed on") { kept.isNotEmpty() }
        assertEquals(listOf(0L, 30_000L, 60_000L), said("save.ack").map { it.getValue("offset").jsonPrimitive.long })
        assertEquals("girnel-board.json", kept.single().first)
        assertTrue(bytes.contentEquals(kept.single().second))
        Thread.sleep(100)
        assertFalse(answered("r1"), "not answered while the person is choosing where it goes")
        chosen.complete(null)
        waitFor("answered") { answered("r1") }
        assertEquals(true, reply("r1")["ok"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test fun `says in words when the export was not kept, could not be written, or another is still being saved`() {
        val answers = java.util.concurrent.LinkedBlockingQueue<suspend () -> String?>()
        val b = bridge(offers = RoomBoardBridge.Offers(save = true), hooks = RoomBoardBridge.Hooks(save = { _, _ -> answers.take()() }))
        b.say(buildJsonObject { put("t", "ready") })
        answers.put { "Not saved: no place was chosen." }
        b.saveIn("r1", "{}".toByteArray())
        waitFor("r1") { answered("r1") }
        assertEquals("Not saved: no place was chosen.", reply("r1").getValue("error").jsonPrimitive.content)
        answers.put { throw IllegalStateException("disk full") }
        b.saveIn("r2", "{}".toByteArray())
        waitFor("r2") { answered("r2") }
        assertEquals("This export could not be saved.", reply("r2").getValue("error").jsonPrimitive.content)
        val gate = kotlinx.coroutines.CompletableDeferred<String?>()
        answers.put { gate.await() }
        b.saveIn("r3", "{}".toByteArray())
        b.saveIn("r4", "{}".toByteArray())
        waitFor("r4") { answered("r4") }
        assertEquals("Another export is being saved. Try again once it is done.", reply("r4").getValue("error").jsonPrimitive.content)
        gate.complete(null)
        waitFor("r3") { answered("r3") }
        assertEquals(true, reply("r3")["ok"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test fun `refuses an export it does not offer, one too large, or one sent wrongly, and drops one the page stops`() {
        val plain = bridge()
        plain.say(buildJsonObject { put("t", "ready") })
        plain.saveIn("r1", "{}".toByteArray())
        waitFor("r1") { answered("r1") }
        assertEquals("This app cannot save exports.", reply("r1").getValue("error").jsonPrimitive.content)

        val kept = CopyOnWriteArrayList<String>()
        val b = bridge(offers = RoomBoardBridge.Offers(save = true), hooks = RoomBoardBridge.Hooks(save = { name, _ -> kept += name; null }))
        b.say(buildJsonObject { put("t", "ready") })
        b.ask("save", "r2", "name" to JsonPrimitive("big.json"), "size" to JsonPrimitive(RoomBoardBridge.MAX_SAVE_BYTES + 1))
        waitFor("r2") { answered("r2") }
        assertEquals("This export is too large for this app to save.", reply("r2").getValue("error").jsonPrimitive.content)
        b.ask("save", "r3", "name" to JsonPrimitive("a.json"), "size" to JsonPrimitive(10))
        b.ask("save.chunk", "r3", "offset" to JsonPrimitive(4), "data" to JsonPrimitive("AQID"))
        waitFor("r3") { answered("r3") }
        assertEquals("The board sent its export wrongly. Nothing was saved.", reply("r3").getValue("error").jsonPrimitive.content)
        b.ask("save", "r4", "name" to JsonPrimitive("a.json"), "size" to JsonPrimitive(40_000))
        b.ask("save.chunk", "r4", "offset" to JsonPrimitive(0), "data" to JsonPrimitive(Base64.getEncoder().encodeToString(ByteArray(30_000))))
        b.ask("save.cancel", "r4")
        b.saveIn("r5", "{}".toByteArray(), name = "after.json")
        waitFor("r5") { answered("r5") }
        assertEquals(true, reply("r5")["ok"]?.jsonPrimitive?.content?.toBoolean())
        assertFalse(answered("r4"))
        assertEquals(listOf("after.json"), kept.toList())
    }

    @Test fun `names an export as the page suggests, with no folders, controls or characters a file cannot have`() {
        assertEquals("passwd.json", RoomBoardBridge.saveName("../../etc/passwd"))
        assertEquals("board.json", RoomBoardBridge.saveName(null))
        assertEquals("board.json", RoomBoardBridge.saveName("..."))
        assertEquals("evil.json", RoomBoardBridge.saveName("‮evil.json"))
        assertEquals("ab.JSON", RoomBoardBridge.saveName("a<b>.JSON"))
        assertEquals("girnel-board.json", RoomBoardBridge.saveName("girnel-board.json"))
    }
}
