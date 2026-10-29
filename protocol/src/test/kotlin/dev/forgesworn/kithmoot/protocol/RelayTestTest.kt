package dev.forgesworn.kithmoot.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** This client's writer against its own reader, and the edges the shared vectors leave out. */
class RelayTestTest {
    private val endpoint = "spry://1b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f?relay=wss%3A%2F%2Frelay.example%2F"
    private val roomId = "a1".repeat(32)
    private val tester = ByteArray(32) { 6 }
    private val host = ByteArray(32) { 5 }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun `a result written here opens on the host with the signed time`() {
        val result = RelayTestResult.of(RelayTestRequest("0123456789abcdef", endpoint), RelayTestVerdict(ok = false, stage = "inbound", sameNetwork = true))
        val wrap = wrapSignal(SignalBody(SIGNAL_RELAY_TEST_RESULT, roomId, relayTest = result.toJson()), tester,
            dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(host), createdAt = 1_800_000_012).wrap
        val opened = unwrapSignal(wrap, host, roomId, now = 1_800_000_012)
        assertNotNull(opened)
        assertEquals(1_800_000_012L, opened!!.createdAt)
        assertEquals(result, RelayTestResult.parse(opened.body.relayTest))
        assertEquals(json("""{"id":"0123456789abcdef","endpoint":"$endpoint","ok":false,"stage":"inbound","sameNetwork":true}"""), result.toJson())
    }

    @Test fun `a pass is written with no stage and no sameNetwork`() {
        assertEquals(json("""{"id":"0123456789abcdef","endpoint":"$endpoint","ok":true}"""),
            RelayTestResult.of(RelayTestRequest("0123456789abcdef", endpoint), RelayTestVerdict(ok = true)).toJson())
    }

    @Test fun `null in any field refuses the body`() {
        assertFalse(isValidRelayTestResult(json("""{"id":"0123456789abcdef","endpoint":"$endpoint","ok":true,"stage":null}""")))
        assertFalse(isValidRelayTestResult(json("""{"id":"0123456789abcdef","endpoint":"$endpoint","ok":false,"stage":null}""")))
        assertFalse(isValidRelayTestResult(json("""{"id":"0123456789abcdef","endpoint":"$endpoint","ok":true,"sameNetwork":null}""")))
        assertFalse(isValidRelayTestRequest(json("""{"id":null,"endpoint":"$endpoint"}""")))
        assertFalse(isValidRelayTestRequest(Json.parseToJsonElement("[]")))
    }

    @Test fun `an endpoint over 1024 characters or not a spry address is refused`() {
        val long = endpoint + "&relay=" + "wss%3A%2F%2Fr.example%2F" + "x".repeat(1024)
        assertFalse(isValidRelayTestRequest(json("""{"id":"0123456789abcdef","endpoint":"$long"}""")))
        assertFalse(isValidRelayTestRequest(json("""{"id":"0123456789abcdef","endpoint":"https://relay.example"}""")))
    }

    @Test fun `a relay-test signal with a body that is not an object is refused`() {
        val wrap = wrapSignal(SignalBody(SIGNAL_RELAY_TEST, roomId), host, dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(tester), createdAt = 1_800_000_000).wrap
        assertNull(unwrapSignal(wrap, tester, roomId, now = 1_800_000_000))
    }
}
