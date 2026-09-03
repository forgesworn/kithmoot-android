package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.peekRekeyEpoch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one part of the epoch group this client implements: noticing that a
 * room has moved on without it.
 *
 * Following an epoch needs the sealed key path and is not built here yet, so
 * the rest of the `roomEpoch` group is counted by `VectorCoverageTest` and not
 * run. What is run is the peek, because the alternative to noticing is a room
 * that goes silent for a reason the person is never told.
 */
class RoomEpochPeekVectorsTest {

    private fun vector(name: String): JsonObject =
        Vectors.group("roomEpoch").first { it.text("name") == name }

    private fun peekOf(v: JsonObject, roomId: String, authority: String): Int? =
        peekRekeyEpoch(
            NostrEvent.fromJson(v.child("input").child("event")),
            roomId,
            authority,
        )

    /** The room and authority every rekey vector is built against. */
    private val roomId: String get() = vector("rekey").child("expected").child("decode").text("roomId")
    private val authority: String get() = vector("rekey").child("expected").child("decode").text("authority")

    @Test
    fun `a rekey names the epoch it moves the room to`() {
        val v = vector("rekey")
        assertEquals(
            "peek for rekey",
            v.child("output")["peek"]!!.jsonPrimitive.int,
            peekOf(v, roomId, authority),
        )
    }

    @Test
    fun `a rekey signed by anybody but the authority is not a rekey`() {
        // Every member of a room holds the room key. Without this check any
        // of them could make every client in the room announce that it had
        // moved on, which is a nuisance rather than a breach - and still not
        // something a client should be talked into.
        val v = vector("rekey-not-the-authority")
        assertNull("a rekey from a member must not peek", peekOf(v, roomId, authority))
    }

    @Test
    fun `a rekey that skips epochs still says which epoch it names`() {
        // Refused for APPLYING - the reference only ever applies the next one
        // - but the peek still answers, which is exactly how a client that has
        // fallen several epochs behind knows how far behind it is.
        val v = vector("rekey-skips-an-epoch")
        assertEquals(
            "peek for a skipped epoch",
            v.child("output")["peek"]!!.jsonPrimitive.int,
            peekOf(v, roomId, authority),
        )
    }

    @Test
    fun `a rekey for another room is not this room's business`() {
        val v = vector("rekey")
        assertNull(peekOf(v, "ff".repeat(32), authority))
    }
}
