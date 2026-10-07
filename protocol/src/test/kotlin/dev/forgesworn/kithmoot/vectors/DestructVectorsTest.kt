package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomAdmission
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.RoomInvitation
import dev.forgesworn.kithmoot.protocol.RoomInvitationHost
import dev.forgesworn.kithmoot.protocol.decodeInvitationRetirement
import dev.forgesworn.kithmoot.protocol.decodePersistentInvitation
import dev.forgesworn.kithmoot.protocol.decodeRekeyEvent
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.encodePersistentInvitation
import dev.forgesworn.kithmoot.protocol.encodeRekeyEvent
import dev.forgesworn.kithmoot.protocol.readRekeyEvidence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fold-kit 0.9.0's `vectors/destruct-vectors.json`, copied byte for byte (never edited) as its
 * own resource beside `member-epoch-vectors.json`: the self-destruct flag on a group invitation,
 * a retirement and a closing rekey. Every event this client can write is rebuilt from its
 * recorded draws and must come out identical; every event is read by the real decoders and
 * must give the recorded result. `oldReader` (what a 0.8.0 reader makes of each) is fold-kit's
 * own check and is not repeated here. The retirement's flag is not read by this client, as the
 * web client does not use it: a flagged retirement must still be a retirement.
 */
class DestructVectorsTest {
    private val root: JsonObject by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/destruct-vectors.json")) { "destruct-vectors.json is missing" }
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }
    private val vectors: List<JsonObject> get() = root.child("groups").list("destruct").map { it.jsonObject }
    private fun vec(name: String): JsonObject = vectors.single { it.text("name") == name }
    private fun draws(value: JsonObject): List<ByteArray> = value.strings("randomHex").map { it.hexToBytes() }
    private fun event(value: JsonObject): NostrEvent = NostrEvent.fromJson(value)
    private fun invitation(input: JsonObject): RoomInvitation = input.child("invitation").let {
        RoomInvitation(it.bytes("bearerHex"), it.text("inviter"), it.flag("persistent"))
    }
    private fun welcomeKey(invitation: RoomInvitation): ByteArray =
        Digests.hkdfSha256(invitation.bearer, null, "kithmoot/v3/group-invitation-key".toByteArray(), 32)

    private fun assertAdmission(label: String, expected: JsonObject, actual: RoomAdmission?) {
        assertNotNull(label, actual)
        assertEquals("$label secret", expected.text("secretHex"), actual!!.secret.toHex())
        assertEquals("$label epoch", expected.number("epoch").toInt(), actual.epoch)
        assertEquals("$label ends", if ("endsAt" in expected) expected.number("endsAt") else null, actual.endsAt)
        assertEquals("$label relays", if ("relays" in expected) expected.strings("relays") else null, actual.relays)
        assertEquals("$label destruct", "destruct" in expected && expected.flag("destruct"), actual.destruct)
        assertNull("$label delegate", actual.delegate)
    }

    @Test fun `the file is in the KithMoot vector format and every vector is counted`() {
        assertEquals("kithmoot/v1", root.text("protocolVersion"))
        assertEquals(setOf("destruct"), root.child("groups").keys)
        assertEquals(
            listOf("destruct-invitation", "destruct-invitation-no-end", "destruct-invitation-malformed", "destruct-invitation-copies",
                "destruct-retirement", "destruct-retirement-not-ended", "destruct-closure", "destruct-rekey-open"),
            vectors.map { it.text("name") },
        )
        assertEquals(3, vectors.count { it.text("kind") == "negative" })
        for (v in vectors) {
            assertTrue(v.text("kind") in setOf("positive", "negative"))
            v.text("note")
        }
    }

    @Test fun `a flagged invitation rebuilds byte for byte, and so does the same one unflagged`() {
        for (name in listOf("destruct-invitation", "destruct-invitation-no-end")) {
            val v = vec(name)
            val i = v.child("input")
            val out = v.child("output")
            val invitation = invitation(i)
            val host = RoomInvitationHost(invitation, i.bytes("inviterSkHex"))
            val random = draws(i)
            val ends = if ("endsAt" in i) i.number("endsAt") else null
            val relays = if ("relays" in i) i.strings("relays") else null
            val rebuilt = encodePersistentInvitation(host, i.bytes("roomSecretHex"), i.number("createdAt"), random[0], random[1],
                ends = ends, relays = relays, destruct = true)
            assertEquals(name, i.child("event"), rebuilt.toJson())
            assertEquals("$name body", out.text("bodyJson"), Nip44.decrypt(rebuilt.content, welcomeKey(invitation)))
            assertAdmission(name, out.child("admission"), decodePersistentInvitation(event(i.child("event")), invitation))

            val unflagged = i.childOrNull("unflagged") ?: continue
            val plain = draws(unflagged)
            val rebuiltPlain = encodePersistentInvitation(host, i.bytes("roomSecretHex"), i.number("createdAt"), plain[0], plain[1],
                ends = ends, relays = relays)
            assertEquals("$name unflagged", unflagged.child("event"), rebuiltPlain.toJson())
            assertEquals("$name unflagged body", out.text("unflaggedBodyJson"), Nip44.decrypt(rebuiltPlain.content, welcomeKey(invitation)))
            assertEquals("the unflagged body is the flagged one without the flag",
                out.text("bodyJson").replace("\"destruct\":true,", ""), out.text("unflaggedBodyJson"))
            assertAdmission("$name unflagged", out.child("unflaggedAdmission"), decodePersistentInvitation(event(unflagged.child("event")), invitation))
        }
    }

    @Test fun `a destruct that is not exactly true refuses the whole envelope`() {
        val v = vec("destruct-invitation-malformed")
        val i = v.child("input")
        val invitation = invitation(i)
        val cases = i.child("cases")
        assertEquals(setOf("false", "string", "number", "null"), cases.keys)
        for ((label, raw) in cases) {
            val case = raw.jsonObject
            assertEquals("$label body", case.text("bodyJson"), Nip44.decrypt(case.child("event").text("content"), welcomeKey(invitation)))
            assertNull(label, decodePersistentInvitation(event(case.child("event")), invitation))
            assertTrue("$label is recorded as refused", v.child("output").child("admission").isNull(label))
        }
    }

    @Test fun `each copy reads alone as recorded`() {
        val v = vec("destruct-invitation-copies")
        val i = v.child("input")
        val invitation = invitation(i)
        val alone = v.child("output").child("alone")
        for (which in listOf("older", "newer")) {
            assertAdmission(which, alone.child(which), decodePersistentInvitation(event(i.child(which).child("event")), invitation))
        }
    }

    @Test fun `a flagged retirement, and a flag without ended, are still retirements`() {
        val flagged = vec("destruct-retirement").child("input")
        val invitation = invitation(flagged)
        assertTrue(decodeInvitationRetirement(event(flagged.child("event")), invitation))
        assertTrue(decodeInvitationRetirement(event(flagged.child("unflagged").child("event")), invitation))
        val notEnded = vec("destruct-retirement-not-ended").child("input")
        assertTrue("a retired link stays retired", decodeInvitationRetirement(event(notEnded.child("event")), invitation(notEnded)))
    }

    @Test fun `a self-destructing closure rebuilds byte for byte and both readers report the flag`() {
        val v = vec("destruct-closure")
        val i = v.child("input")
        val out = v.child("output")
        val previous = i.child("previous")
        val next = i.child("next")
        val current = deriveEpoch(RoomEpoch(previous.number("epoch").toInt(), previous.bytes("secretHex")))
        val random = draws(i)
        assertEquals("a closure seals to nobody: the body nonce and the aux-rand only", 2, random.size)
        fun rebuild(destruct: Boolean, draws: List<ByteArray>) = encodeRekeyEvent(
            i.text("roomId"), i.bytes("authoritySkHex"), current, RoomEpoch(next.number("epoch").toInt(), next.bytes("secretHex")),
            i.strings("recipients"), emptyList(), i.number("createdAt"), closed = true, destruct = destruct,
            bodyNonce = draws[0], auxRand = draws[1],
        )
        val flagged = rebuild(true, random)
        assertEquals(i.child("event"), flagged.toJson())
        assertEquals(out.text("bodyJson"), Nip44.decrypt(flagged.content, current.key))
        val plain = rebuild(false, draws(i.child("unflagged")))
        assertEquals(i.child("unflagged").child("event"), plain.toJson())
        assertEquals(out.text("unflaggedBodyJson"), Nip44.decrypt(plain.content, current.key))

        for ((label, source, expected) in listOf(
            Triple("flagged", i.child("event"), out.child("notice")),
            Triple("unflagged", i.child("unflagged").child("event"), out.child("unflaggedNotice")),
        )) {
            val notice = decodeRekeyEvent(event(source), i.text("roomId"), i.text("authority"), current, i.bytes("deviceSkHex"))
            assertNotNull(label, notice)
            assertEquals(expected.number("epoch").toInt(), notice!!.epoch)
            assertEquals(expected.strings("removed"), notice.removed)
            assertEquals(expected.flag("closed"), notice.closed)
            assertEquals(expected.number("at"), notice.at)
            assertEquals("$label destruct", "destruct" in expected, notice.destruct)
            assertNull("a closure seals no secret", notice.secret)
        }
        val evidence = readRekeyEvidence(event(i.child("event")), i.text("roomId"), i.text("authority"), current.epoch, current.key)
        assertNotNull(evidence)
        val wanted = out.child("evidence")
        assertEquals(wanted.number("epoch").toInt(), evidence!!.epoch)
        assertEquals(wanted.strings("removed"), evidence.removed)
        assertTrue(evidence.closed)
        assertTrue(evidence.destruct)
        assertFalse(readRekeyEvidence(event(i.child("unflagged").child("event")), i.text("roomId"), i.text("authority"), current.epoch, current.key)!!.destruct)
    }

    @Test fun `the flag on an open rekey is refused by the encoder and dropped by both readers`() {
        val v = vec("destruct-rekey-open")
        val i = v.child("input")
        val out = v.child("output")
        val previous = i.child("previous")
        val current = deriveEpoch(RoomEpoch(previous.number("epoch").toInt(), previous.bytes("secretHex")))
        for (case in listOf("withRemoval", "withScheduled")) {
            val source = i.child(case)
            assertEquals("$case body", source.text("bodyJson"), Nip44.decrypt(source.child("event").text("content"), current.key))
            val expected = out.child(case)
            val notice = decodeRekeyEvent(event(source.child("event")), i.text("roomId"), i.text("authority"), current, i.bytes("deviceSkHex"))
            assertNotNull(case, notice)
            val wantedNotice = expected.child("notice")
            assertEquals(wantedNotice.strings("removed"), notice!!.removed)
            assertFalse(notice.closed)
            assertEquals("$case scheduled", "scheduled" in wantedNotice, notice.scheduled)
            assertFalse("$case destruct", notice.destruct)
            assertEquals(wantedNotice.number("at"), notice.at)
            val evidence = readRekeyEvidence(event(source.child("event")), i.text("roomId"), i.text("authority"), current.epoch, current.key)
            assertNotNull(case, evidence)
            assertEquals(expected.child("evidence").strings("removed"), evidence!!.removed)
            assertEquals("$case evidence scheduled", "scheduled" in expected.child("evidence"), evidence.scheduled)
            assertFalse("$case evidence destruct", evidence.destruct)
            assertEquals("refused", out.child("encoder").text(case))
        }
        val removed = out.child("withRemoval").child("notice").strings("removed")
        val next = RoomEpoch(2, ByteArray(32) { 9 })
        assertThrows(IllegalArgumentException::class.java) {
            encodeRekeyEvent(i.text("roomId"), ByteArray(32) { 1 }, current, next, emptyList(), removed, 1_800_000_000, destruct = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            encodeRekeyEvent(i.text("roomId"), ByteArray(32) { 1 }, current, next, emptyList(), emptyList(), 1_800_000_000, scheduled = true, destruct = true)
        }
    }

    @Test fun `the decoded secret is the room's own`() {
        val i = vec("destruct-invitation").child("input")
        val admission = decodePersistentInvitation(event(i.child("event")), invitation(i))!!
        assertArrayEquals(i.bytes("roomSecretHex"), admission.secret)
    }
}
