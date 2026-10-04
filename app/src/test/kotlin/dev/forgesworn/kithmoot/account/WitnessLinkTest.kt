package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.relay.LinkJsonRequest
import dev.forgesworn.kithmoot.relay.LinkJsonResponse
import dev.forgesworn.kithmoot.relay.LinkJsonTransport
import dev.forgesworn.kithmoot.relay.LinkPathState
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/** The witness answers as the note's table maps them (P3-03b-2). */
class WitnessLinkTest {
    private val path = LinkPathState("direct", null, "quic://box", "")

    private fun receipt(status: Int, size: Int = 170, version: Int = 1) = ByteArray(size) { 0x33 }.also {
        if (size > 1) { it[0] = version.toByte(); it[1] = status.toByte() }
    }

    private fun answer(status: Int, body: ByteArray, refused: Boolean = false) =
        WitnessLink.witnessAnswer(LinkJsonResponse(status, body, path, refused))

    @Test fun `a receipt whose status byte matches the HTTP status is a receipt`() {
        for ((http, byte) in listOf(200 to 0, 409 to 1, 410 to 2)) {
            val body = receipt(byte)
            val mapped = assertIs<WitnessAnswer.Receipt>(answer(http, body))
            assertContentEquals(body, mapped.bytes)
        }
    }

    @Test fun `a status mismatch, another length or another version is unavailable`() {
        assertEquals(WitnessAnswer.Unavailable, answer(200, receipt(1)))
        assertEquals(WitnessAnswer.Unavailable, answer(409, receipt(0)))
        assertEquals(WitnessAnswer.Unavailable, answer(410, receipt(0)))
        assertEquals(WitnessAnswer.Unavailable, answer(200, receipt(0, size = 169)))
        assertEquals(WitnessAnswer.Unavailable, answer(200, receipt(0, size = 171)))
        assertEquals(WitnessAnswer.Unavailable, answer(200, receipt(0, version = 2)))
    }

    @Test fun `an empty 409 is unavailable, since the coordinator fences on exhaustion itself`() {
        assertEquals(WitnessAnswer.Unavailable, answer(409, ByteArray(0)))
    }

    @Test fun `only the bridge's marked 403 is a refusal`() {
        assertEquals(WitnessAnswer.Refused, answer(403, ByteArray(0), refused = true))
        assertEquals(WitnessAnswer.Unavailable, answer(403, ByteArray(0), refused = false))
        assertEquals(WitnessAnswer.Unavailable, answer(401, ByteArray(0), refused = true))
        assertEquals(WitnessAnswer.Unavailable, answer(500, receipt(0)))
        assertEquals(WitnessAnswer.Unavailable, answer(404, ByteArray(0)))
    }

    @Test fun `requests are POSTs on the given route with an empty authorisation`() = runBlocking<Unit> {
        val seen = mutableListOf<LinkJsonRequest>()
        val transport = LinkJsonTransport { request ->
            seen += request
            CompletableFuture.completedFuture(LinkJsonResponse(200, receipt(0), path))
        }
        val link = WitnessLink(transport, "route-witness")
        assertIs<WitnessAnswer.Receipt>(link.read(byteArrayOf(1, 2, 3)))
        assertIs<WitnessAnswer.Receipt>(link.advance(byteArrayOf(4, 5)))
        assertEquals(listOf("/vmls-witness/v1/read", "/vmls-witness/v1/advance"), seen.map { it.path })
        assertEquals(setOf("POST"), seen.map { it.method }.toSet())
        assertEquals(setOf(""), seen.map { it.authorization }.toSet())
        assertEquals(setOf("route-witness"), seen.map { it.routeId }.toSet())
        assertContentEquals(byteArrayOf(1, 2, 3), seen[0].body)
    }

    @Test fun `a failed or unanswered exchange is unavailable`() = runBlocking<Unit> {
        val failing = WitnessLink({ CompletableFuture.failedFuture(IllegalStateException("Unknown Link route")) }, "route")
        assertEquals(WitnessAnswer.Unavailable, failing.read(byteArrayOf(1)))
        val throwing = WitnessLink({ throw IllegalStateException("stopped") }, "route")
        assertEquals(WitnessAnswer.Unavailable, throwing.advance(byteArrayOf(1)))
        val silent = WitnessLink({ CompletableFuture<LinkJsonResponse>() }, "route", timeoutMillis = 50)
        assertEquals(WitnessAnswer.Unavailable, silent.read(byteArrayOf(1)))
    }
}
