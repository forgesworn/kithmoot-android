package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.DeadDrop
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The dead-drop derivation against `nostr-deaddrop`'s known answers, loaded
 * verbatim from its `vectors/deaddrop.json`. A copy, never an edit: where
 * this implementation and the vectors disagree, that is the finding.
 */
class DeadDropVectorsTest {

    private val root: JsonObject by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/deaddrop-vectors.json")) { "deaddrop-vectors.json is missing" }
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    private fun caseOf(byte: Int): DeadDrop.Case = DeadDrop.Case.entries.first { it.byte == byte }

    @Test
    fun everyCaseDerivesTheSameKey() {
        val cases = root.getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(7, cases.size)
        for (c in cases) {
            val name = c.text("name")
            val ikm = DeadDrop.Ikm(c.bytes("ikmHex"), caseOf(c.getValue("caseByte").jsonPrimitive.int))
            val key = DeadDrop.deriveDropKey(ikm, c.number("epochIndex"), c.text("sender"), c.getValue("counter").jsonPrimitive.int)
            assertEquals("private key for $name", c.text("dropPrivHex"), key.privateKey.toHex())
            assertEquals("public key for $name", c.text("dropPubXOnly"), key.publicKey)
        }
    }

    @Test
    fun thePairIkmIsLinksRendezvousMaterial() {
        val keys = root.getValue("testOnlyKeys").jsonObject
        val a = keys.bytes("nostrAPrivHex")
        val b = keys.bytes("nostrBPrivHex")
        val aPub = keys.text("nostrAPubXOnly")
        val bPub = keys.text("nostrBPubXOnly")
        val ephA = keys.bytes("ephAPrivHex")
        val ephB = keys.bytes("ephBPrivHex")
        val ephAPub = keys.text("ephAPubXOnly")
        val ephBPub = keys.text("ephBPubXOnly")
        val byName = root.getValue("cases").jsonArray.map { it.jsonObject }.associateBy { it.text("name") }

        // Both sides reach the same bytes from their own material.
        val none = byName.getValue("no-ephemeral-A-sends")
        assertEquals(none.text("ikmHex"), DeadDrop.pairIkm(a, bPub).bytes.toHex())
        assertEquals(none.text("ikmHex"), DeadDrop.pairIkm(b, aPub).bytes.toHex())
        val both = byName.getValue("both-ephemeral")
        assertEquals(both.text("ikmHex"), DeadDrop.pairIkm(a, bPub, ephA, ephBPub).bytes.toHex())
        assertEquals(both.text("ikmHex"), DeadDrop.pairIkm(b, aPub, ephB, ephAPub).bytes.toHex())
        val one = byName.getValue("one-ephemeral-A-carries")
        assertEquals(one.text("ikmHex"), DeadDrop.pairIkm(a, bPub, ephA, null).bytes.toHex())
        assertEquals(one.text("ikmHex"), DeadDrop.pairIkm(b, aPub, null, ephAPub).bytes.toHex())
    }

    @Test
    fun everyRoomCaseDerivesTheSameKey() {
        val cases = root.getValue("roomCases").jsonArray.map { it.jsonObject }
        assertEquals(2, cases.size)
        for (c in cases) {
            val name = c.text("name")
            val ikm = DeadDrop.roomIkm(c.bytes("roomKeyHex"))
            assertEquals("room ikm for $name", c.text("ikmHex"), ikm.bytes.toHex())
            assertEquals(DeadDrop.Case.ROOM, ikm.case)
            val key = DeadDrop.deriveDropKey(ikm, c.number("epochIndex"), c.text("member"), c.getValue("counter").jsonPrimitive.int)
            assertEquals("private key for $name", c.text("dropPrivHex"), key.privateKey.toHex())
            assertEquals("public key for $name", c.text("dropPubXOnly"), key.publicKey)
        }
    }

    @Test
    fun aRoomIkmHasItsOwnCaseByteAndSixteenKeysAMemberAnHour() {
        val ikm = DeadDrop.roomIkm(ByteArray(32) { 7 })
        assertEquals(65, ikm.bytes.size)
        assertEquals(0x10, ikm.bytes[0].toInt())
        assertEquals("0".repeat(64), ikm.bytes.copyOfRange(33, 65).toHex())
        val member = "3e0b147852ddd35607a06b4bb56ffc102e4d7b3ece162042c3f32f96a49c4613"
        val epoch = DeadDrop.deriveDropEpoch(ikm, 498216, member, DeadDrop.MAX_PER_EPOCH_ROOM)
        assertEquals(16, epoch.map { it.publicKey }.toSet().size)
        assertNotEquals(epoch[0].privateKey.toHex(), epoch[1].privateKey.toHex())
    }

    @Test
    fun theEpochIndexIsTheHourFloor() {
        assertEquals(498216L, DeadDrop.epochIndexAt(498216L * 3600 + 1799))
        assertEquals(498217L, DeadDrop.epochIndexAt(498217L * 3600))
    }
}
