package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.EpochGrant
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.HISTORY_WINDOW_SECONDS
import dev.forgesworn.kithmoot.protocol.LeftEpoch
import dev.forgesworn.kithmoot.protocol.MAX_HISTORY_EPOCHS
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RekeyEvidence
import dev.forgesworn.kithmoot.protocol.RekeyNotice
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.decodeEpochGrant
import dev.forgesworn.kithmoot.protocol.decodeRekeyEvent
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.encodeEpochGrant
import dev.forgesworn.kithmoot.protocol.encodeRekeyEvent
import dev.forgesworn.kithmoot.protocol.epochCommitment
import dev.forgesworn.kithmoot.protocol.epochsInWindow
import dev.forgesworn.kithmoot.protocol.readRekeyEvidence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * fold-kit 0.8.0's `vectors/schedule-vectors.json`, copied byte for byte (never edited),
 * reproduced the way its own `vectors/verify-schedule.test.ts` does: every event a real encoder
 * wrote is rebuilt from its recorded random draws and must come out identical, every event
 * built by hand is rebuilt from its recorded body, nonce and aux-rand, and every reader is the
 * real decoder. See fold-kit `docs/scheduled-rekey.md`.
 */
class ScheduleVectorsTest {
    private val root: JsonObject by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/schedule-vectors.json")) { "schedule-vectors.json is missing" }
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }
    private val vectors: List<JsonObject> get() = root.child("groups").list("schedule").map { it.jsonObject }
    private fun vec(name: String): JsonObject = vectors.single { it.text("name") == name }
    private fun draws(value: JsonObject): List<ByteArray> = value.strings("randomHex").map { it.hexToBytes() }
    private fun event(value: JsonObject): NostrEvent = NostrEvent.fromJson(value)
    private fun epochOf(value: JsonObject) = RoomEpoch(value.number("epoch").toInt(), value.bytes("secretHex"))

    /** A hand-built event, from its recorded body, nonce and aux-rand. */
    private fun rebuild(built: JsonObject, key: ByteArray, authoritySecret: ByteArray): NostrEvent {
        val (nonce, aux) = draws(built)
        val shape = built.child("event")
        val tags = shape.list("tags").map { tag -> (tag as JsonArray).map { it.jsonPrimitive.content } }
        return Events.sign(authoritySecret, shape.number("kind").toInt(), shape.number("created_at"), tags,
            Nip44.encrypt(built.text("bodyJson"), key, nonce), aux)
    }

    /** The notice as the vector writes it: optional fields only when set. */
    private fun notice(n: RekeyNotice) = buildJsonObject {
        put("epoch", n.epoch)
        put("removed", strings(n.removed))
        put("closed", n.closed)
        if (n.scheduled) put("scheduled", true)
        n.members?.let { put("members", strings(it)) }
        n.secret?.let { put("secretHex", it.toHex()) }
        put("at", n.at)
    }

    private fun evidence(e: RekeyEvidence) = buildJsonObject {
        put("epoch", e.epoch)
        put("removed", strings(e.removed))
        put("closed", e.closed)
        if (e.scheduled) put("scheduled", true)
        e.commit?.let { put("commit", it) }
        e.members?.let { put("members", strings(it)) }
    }

    private fun grant(g: EpochGrant?) = when (g) {
        is EpochGrant.Current -> buildJsonObject {
            put("epoch", g.epoch)
            put("secretHex", requireNotNull(g.secret).toHex())
            put("removed", strings(g.removed))
            if (g.passed.isNotEmpty()) put("passed", buildJsonArray {
                g.passed.forEach { p -> add(buildJsonObject { put("epoch", p.epoch); put("secretHex", p.secret.toHex()); put("leftAt", p.leftAt) }) }
            })
        }
        else -> null
    }

    private fun strings(values: List<String>) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    @Test fun `the file is in the KithMoot vector format and every vector is counted`() {
        assertEquals("kithmoot/v1", root.text("protocolVersion"))
        assertEquals(setOf("schedule"), root.child("groups").keys)
        assertEquals(
            listOf("scheduled-rekey", "scheduled-rekey-contradictory", "epoch-grant-window", "epoch-grant-window-old-reader", "history-window"),
            vectors.map { it.text("name") },
        )
        assertEquals(1, vectors.count { it.text("kind") == "negative" })
        for (v in vectors) {
            assertTrue(v.text("kind") in setOf("positive", "negative"))
            v.text("note")
        }
    }

    @Test fun `scheduled-rekey rebuilds byte for byte and reads as scheduled from the epoch it leaves`() {
        val v = vec("scheduled-rekey")
        val i = v.child("input")
        val out = v.child("output")
        val previous = deriveEpoch(epochOf(i.child("previous")))
        val recipients = i.strings("recipients")
        fun encode(random: List<ByteArray>, scheduled: Boolean) = encodeRekeyEvent(
            i.text("roomId"), i.bytes("authoritySkHex"), previous, epochOf(i.child("next")), recipients, emptyList(),
            i.number("createdAt"), commit = true, members = i.strings("members"), scheduled = scheduled,
            recipientNonces = recipients.withIndex().associate { (n, r) -> r to random[n] },
            bodyNonce = random[recipients.size], auxRand = random[recipients.size + 1],
        )
        assertEquals(i.child("event"), encode(draws(i), scheduled = true).toJson())
        assertEquals(i.child("unflagged").child("event"), encode(draws(i.child("unflagged")), scheduled = false).toJson())

        // A second way: the marker is in the body, and nowhere on the wire.
        val body = Nip44.decrypt(i.child("event").text("content"), previous.key)
        assertEquals(out.text("bodyJson"), body)
        val unflaggedBody = Nip44.decrypt(i.child("unflagged").child("event").text("content"), previous.key)
        assertEquals(out.text("unflaggedBodyJson"), unflaggedBody)
        assertEquals(body.replace("\"scheduled\":true,", ""), unflaggedBody)
        assertEquals(listOf(listOf("d", i.text("roomId")), listOf("epoch", "2")), event(i.child("event")).tags)

        val read = { e: JsonObject -> decodeRekeyEvent(event(e), i.text("roomId"), i.text("authority"), previous, i.bytes("deviceSkHex")) }
        val scheduled = assertNotNullAnd(read(i.child("event")))
        assertTrue(scheduled.scheduled)
        assertEquals(out.child("notice"), notice(scheduled))
        val unflagged = assertNotNullAnd(read(i.child("unflagged").child("event")))
        assertFalse(unflagged.scheduled)
        assertEquals(out.child("unflaggedNotice"), notice(unflagged))

        // The chain runs through it: read with the previous epoch's key alone it checks out,
        // and its commitment matches the secret it carries.
        val ev = assertNotNullAnd(readRekeyEvidence(event(i.child("event")), i.text("roomId"), i.text("authority"), previous.epoch, previous.key))
        assertEquals(out.child("evidence"), evidence(ev))
        assertEquals(epochCommitment(i.text("roomId"), 2, i.child("next").bytes("secretHex")), ev.commit)
        assertEquals(out.text("commit"), ev.commit)
    }

    @Test fun `scheduled-rekey-contradictory is refused by the encoder and read as the removal and the close`() {
        val v = vec("scheduled-rekey-contradictory")
        val i = v.child("input")
        val out = v.child("output")
        val writer = vec("scheduled-rekey").child("input")
        val authoritySecret = writer.bytes("authoritySkHex")
        val previous = deriveEpoch(epochOf(i.child("previous")))
        val next = epochOf(writer.child("next"))
        val gone = Json.parseToJsonElement(i.child("withRemoval").text("bodyJson")).jsonObject.strings("removed").single()
        assertThrows(IllegalArgumentException::class.java) {
            encodeRekeyEvent(i.text("roomId"), authoritySecret, previous, next, emptyList(), listOf(gone), 0, scheduled = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodeRekeyEvent(i.text("roomId"), authoritySecret, previous, next, emptyList(), emptyList(), 0, closed = true, scheduled = true)
        }
        assertEquals(buildJsonObject { put("withRemoval", "refused"); put("withClose", "refused") }, out.child("encoder"))

        for (k in listOf("withRemoval", "withClose")) {
            val built = i.child(k)
            assertEquals(k, built.child("event"), rebuild(built, previous.key, authoritySecret).toJson())
            assertEquals(built.text("bodyJson"), Nip44.decrypt(built.child("event").text("content"), previous.key))
            assertTrue(Json.parseToJsonElement(built.text("bodyJson")).jsonObject.getValue("scheduled").jsonPrimitive.content == "true")
            val n = assertNotNullAnd(decodeRekeyEvent(event(built.child("event")), i.text("roomId"), i.text("authority"), previous, i.bytes("deviceSkHex")))
            assertFalse(k, n.scheduled)
            assertEquals(k, out.child(k).child("notice"), notice(n))
            val ev = assertNotNullAnd(readRekeyEvidence(event(built.child("event")), i.text("roomId"), i.text("authority"), previous.epoch, previous.key))
            assertFalse(k, ev.scheduled)
            assertEquals(k, out.child(k).child("evidence"), evidence(ev))
        }
        assertEquals(1, out.child("withRemoval").child("notice").strings("removed").size)
        assertTrue(out.child("withClose").child("notice").flag("closed"))
    }

    @Test fun `epoch-grant-window carries sixteen, refuses a seventeenth, and a malformed list costs only itself`() {
        val v = vec("epoch-grant-window")
        val i = v.child("input")
        val out = v.child("output")
        val authoritySecret = i.bytes("authoritySkHex")
        val deviceSecret = i.bytes("deviceSkHex")
        val passed = i.list("passed").map { it.jsonObject }.map { LeftEpoch(it.number("epoch").toInt(), it.bytes("secretHex"), it.number("leftAt")) }
        assertEquals(MAX_HISTORY_EPOCHS, passed.size)
        val device = event(i.child("event")).tagValue("p")!!
        assertEquals(Schnorr.publicKeyHex(deviceSecret), device)
        val (nonce, aux) = draws(i)
        fun encode(list: List<LeftEpoch>, nonce: ByteArray = ByteArray(32), aux: ByteArray = ByteArray(32)) = encodeEpochGrant(
            i.text("roomId"), authoritySecret, device, i.text("request"), i.number("now"), epochOf(i.child("epoch")), emptyList(),
            passed = list, nonce = nonce, auxRand = aux,
        )
        assertEquals(i.child("event"), encode(passed, nonce, aux).toJson())

        val key = Nip44.conversationKey(deviceSecret, i.text("authority").hexToBytes())
        val body = Nip44.decrypt(i.child("event").text("content"), key)
        assertEquals(out.text("bodyJson"), body)
        val wire = Json.parseToJsonElement(body).jsonObject.list("passed").map { it.jsonObject }
        assertTrue(wire.all { it.keys.toList() == listOf("epoch", "secret", "left") })
        assertEquals(passed.map { it.secret.toHex() }, wire.map { Base64.getUrlDecoder().decode(it.text("secret")).toHex() })

        val read = { e: JsonObject -> grant(decodeEpochGrant(event(e), i.text("roomId"), i.text("authority"), deviceSecret, i.text("request"), i.number("now"))) }
        assertEquals(out.child("grant"), read(i.child("event")))
        assertEquals(i.list("passed"), out.child("grant").list("passed"))

        val extra = LeftEpoch(1, ByteArray(32) { 1 }, i.number("now") - 17 * 3_600)
        assertThrows(IllegalArgumentException::class.java) { encode(listOf(extra) + passed) }
        assertEquals("refused", out.text("seventeen"))

        val conversation = Nip44.conversationKey(authoritySecret, device.hexToBytes())
        val malformed = i.child("malformed")
        assertEquals(setOf("overCap", "unsorted", "epochZero"), malformed.keys)
        for ((k, value) in malformed) {
            val built = value.jsonObject
            assertEquals(k, built.child("event"), rebuild(built, conversation, authoritySecret).toJson())
            assertEquals(built.text("bodyJson"), Nip44.decrypt(built.child("event").text("content"), key))
            val got = read(built.child("event"))
            assertEquals(k, out.child("malformed").child(k), got)
            assertEquals(k, buildJsonObject {
                put("epoch", 18); put("secretHex", i.child("epoch").text("secretHex")); put("removed", JsonArray(emptyList()))
            }, got)
        }
        assertEquals(MAX_HISTORY_EPOCHS + 1, Json.parseToJsonElement(malformed.child("overCap").text("bodyJson")).jsonObject.list("passed").size)
    }

    @Test fun `epoch-grant-window-old-reader reads the current epoch, which is the new reader's answer without passed`() {
        val v = vec("epoch-grant-window-old-reader")
        val i = v.child("input")
        assertEquals(vec("epoch-grant-window").child("input").child("event"), i.child("event"))
        val decoded = decodeEpochGrant(event(i.child("event")), i.text("roomId"), i.text("authority"), i.bytes("deviceSkHex"), i.text("request"), i.number("now"))
        val current = decoded as EpochGrant.Current
        assertEquals(MAX_HISTORY_EPOCHS, current.passed.size)
        val withoutPassed = JsonObject(grant(current)!!.filterKeys { it != "passed" })
        assertEquals(v.child("output").child("oldReader"), withoutPassed)
    }

    @Test fun `history-window keeps what every case says`() {
        val v = vec("history-window")
        assertEquals(HISTORY_WINDOW_SECONDS, v.child("input").number("windowSeconds"))
        assertEquals(MAX_HISTORY_EPOCHS.toLong(), v.child("input").number("maxEpochs"))
        val cases = v.child("input").list("cases").map { it.jsonObject }
        val kept = v.child("output").list("cases").map { it.jsonObject }
        assertEquals(cases.map { it.text("name") }, kept.map { it.text("name") })
        cases.zip(kept).forEach { (case, expected) ->
            val left = case.list("left").map { it.jsonObject }
            val got = epochsInWindow(left, case.number("now"), { it.number("epoch").toInt() }, { it.number("leftAt") })
            assertEquals(case.text("name"), expected.list("kept"), JsonArray(got))
        }
        fun epochs(name: String) = kept.single { it.text("name") == name }.list("kept").map { it.jsonObject.number("epoch").toInt() }
        assertEquals(listOf(3, 2), epochs("edge"))
        assertEquals(MAX_HISTORY_EPOCHS, epochs("cap").size)
        assertEquals(listOf(10, 9, 8, 7), epochs("weekly"))
        assertEquals(listOf(5, 4, 2), epochs("unsorted-and-doubled"))
        assertEquals(emptyList<Int>(), epochs("empty"))
    }

    private fun <T : Any> assertNotNullAnd(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
