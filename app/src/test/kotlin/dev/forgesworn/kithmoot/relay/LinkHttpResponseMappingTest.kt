package dev.forgesworn.kithmoot.relay

import dev.forgesworn.link.ffi.LinkHttpResponse
import dev.forgesworn.link.ffi.LinkPath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The bridge's response record maps field for field, including Bothy's witness refusal flag. */
class LinkHttpResponseMappingTest {
    private val path = LinkPath("relayed", "wss://relay.example", null, "")

    @Test
    fun aWitnessRefusalCarriesItsFlag() {
        val mapped = linkJsonResponse(LinkHttpResponse(403u, ByteArray(0), true, path))
        assertEquals(403, mapped.status)
        assertTrue(mapped.witnessRefused)
        assertEquals(LinkPathState("relayed", "wss://relay.example", null, ""), mapped.path)
    }

    @Test
    fun anyOtherReplyIsNotARefusal() {
        val body = ByteArray(170) { it.toByte() }
        val mapped = linkJsonResponse(LinkHttpResponse(200u, body, false, path))
        assertEquals(200, mapped.status)
        assertContentEquals(body, mapped.body)
        assertFalse(mapped.witnessRefused)
        assertFalse(linkJsonResponse(LinkHttpResponse(403u, ByteArray(0), false, path)).witnessRefused)
    }
}
