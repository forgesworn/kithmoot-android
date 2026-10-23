package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.RoomStorageRecord
import dev.forgesworn.kithmoot.protocol.decodeRoomStorageOp
import dev.forgesworn.kithmoot.protocol.encodeRoomStorageOp
import dev.forgesworn.kithmoot.protocol.signRoomStorage
import dev.forgesworn.kithmoot.protocol.verifyRoomStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every shared `roomStorage` vector: the signed storage list the web client's
 * `signRoomStorage` makes, in two orders and withdrawn, and every way a member
 * refuses to believe one. See `room-storage.ts` and the `storage` op in
 * `control.ts` in the TypeScript reference implementation.
 */
class RoomStorageVectorsTest {
    private val group get() = Vectors.group("roomStorage")

    @Test fun `each signed list signs, verifies and round trips as a control op`() {
        for (name in listOf("room-storage-signature", "room-storage-another-order", "room-storage-withdrawn")) {
            val value = group.single { it.text("name") == name }
            val input = value.child("input")
            val output = value.child("output")
            val nodes = input.strings("nodes")
            val sig = signRoomStorage(input.text("roomId"), input.number("version"), nodes, input.bytes("authoritySkHex"), input.bytes("auxRandHex"))
            assertEquals("$name sig", output.text("sig"), sig)
            val verify = value.child("expected").child("verify")
            assertTrue(name, verifyRoomStorage(verify.text("roomId"), verify.number("version"), verify.strings("nodes"), verify.text("sig"), verify.text("authority")))
            assertTrue("$name expected.result", value.child("expected").flag("result"))
            assertEquals("$name message", Digests.sha256(input.text("canonicalMessage").toByteArray()).toHex(),
                Digests.sha256("kithmoot/v1/storage:${input.text("roomId")}:${input.number("version")}:${nodes.joinToString(",", "[", "]") { "\"$it\"" }}".toByteArray()).toHex())
            val record = RoomStorageRecord(nodes, input.number("version"), sig)
            assertEquals("$name op", output.text("text"), encodeRoomStorageOp(record))
            val decoded = decodeRoomStorageOp(output.text("text"))
            assertNotNull("$name decodes", decoded)
            decoded!!
            val result = output.child("result")
            assertEquals("$name nodes", result.strings("nodes"), decoded.nodes)
            assertEquals("$name version", result.number("version"), decoded.version)
            assertEquals("$name decoded sig", result.text("sig"), decoded.sig)
        }
    }

    @Test fun `every negative vector is refused`() {
        val negatives = group.filter { it.text("kind") == "negative" }
        assertEquals(9, negatives.size)
        for (value in negatives) {
            val input = value.child("input")
            assertFalse(value.text("name"), verifyRoomStorage(input.text("roomId"), input.number("version"), input.strings("nodes"), input.text("sig"), input.text("authority")))
            assertFalse(value.child("output").flag("result"))
        }
    }

    @Test fun `the op decoder takes exactly four keys and the right types`() {
        val sig = "ab".repeat(64)
        assertNotNull(decodeRoomStorageOp("""{"op":"storage","nodes":[],"version":1,"sig":"$sig"}"""))
        for (bad in listOf(
            """{"op":"storage","nodes":[],"version":1,"sig":"$sig","extra":1}""",
            """{"op":"storage","nodes":[],"version":"1","sig":"$sig"}""",
            """{"op":"storage","nodes":[],"version":-1,"sig":"$sig"}""",
            """{"op":"storage","nodes":[7],"version":1,"sig":"$sig"}""",
            """{"op":"storage","nodes":"https://a.example","version":1,"sig":"$sig"}""",
            """{"op":"storage","nodes":[],"version":1,"sig":"zz"}""",
            """{"op":"relays","nodes":[],"version":1,"sig":"$sig"}""",
            """{"op":"storage","nodes":[${(0..8).joinToString(",") { "\"https://n$it.example\"" }}],"version":1,"sig":"$sig"}""",
        )) assertEquals(bad, null, decodeRoomStorageOp(bad))
    }
}
