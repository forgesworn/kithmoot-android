package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxCadenceTest {
    private val vectors: JsonObject by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/cadence-v1.json"))
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }

    @Test fun frozenBothyAndWebRequestsKeepExactBytesAndAuthorizationOrder() {
        for (name in listOf("lease", "queue")) {
            val value = vectors.getValue(name).jsonObject
            val body = value.getValue("body").jsonPrimitive.content.toByteArray()
            assertEquals(value.getValue("payload_sha256").jsonPrimitive.content, Digests.sha256(body).toHex())
            val encoded = value.getValue("authorization").jsonPrimitive.content.removePrefix("Nostr ")
            val wire = Json.parseToJsonElement(Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)).jsonObject
            val event = NostrEvent.fromJson(value.getValue("authorization_event"))
            assertEquals(event.toRustWireJson(), wire)
            assertTrue(Events.verify(event))
            assertEquals(value.getValue("method").jsonPrimitive.content, event.tags[1][1])
            assertEquals(value.getValue("payload_sha256").jsonPrimitive.content, event.tags[2][1])
            assertEquals("http://${vectors.getValue("node_id").jsonPrimitive.content}${value.getValue("path").jsonPrimitive.content}", event.tags[0][1])
        }
    }

    @Test fun statusBodyIsSignedByTheDeviceForItsExactBytes() {
        val leaseBody = Json.parseToJsonElement(vectors.getValue("lease").jsonObject.getValue("body").jsonPrimitive.content).jsonObject
        val credential = NostrEvent.fromJson(leaseBody.getValue("credential"))
        val scope = CadenceScope(
            vectors.getValue("node_id").jsonPrimitive.content,
            leaseBody.getValue("room").jsonPrimitive.content,
            leaseBody.getValue("persona").jsonPrimitive.content,
            leaseBody.getValue("device").jsonPrimitive.content,
            credential,
            leaseBody.getValue("grant_id").jsonPrimitive.content,
        )
        val now = vectors.getValue("now").jsonPrimitive.content.toLong()
        val body = BoxCadence.statusBody(scope, "55".repeat(16), now)
        assertEquals(
            "{\"v\":1,\"request_id\":\"${"55".repeat(16)}\",\"server\":\"${leaseBody.getValue("server").jsonPrimitive.content}\",\"room\":\"${scope.room}\",\"persona\":\"${scope.persona}\",\"device\":\"${scope.device}\",\"credential\":${credential.toRustWireJson()},\"grant_id\":\"${scope.grantId}\",\"lease_id\":null,\"generation\":null}",
            body.toString(),
        )

        val signed = BoxCadence.sign(scope.nodeId, "POST", "/cadence/v1/status", body, "dd".repeat(32).hexToBytes(), now, ByteArray(32))
        assertArrayEquals(body.toString().toByteArray(), signed.body)
        assertEquals(Digests.sha256(signed.body).toHex(), signed.payloadSha256)
        assertEquals(scope.device, signed.authorizationEvent.pubkey)
        assertTrue(Events.verify(signed.authorizationEvent))
        val decoded = Base64.getDecoder().decode(signed.authorization.removePrefix("Nostr ")).toString(Charsets.UTF_8)
        assertEquals(signed.authorizationEvent.toRustWireJson().toString(), decoded)
    }

    @Test fun changedScopeAndPublicWebPathsFailBeforeSigning() {
        val value = Json.parseToJsonElement("{\"device\":\"${"00".repeat(32)}\"}").jsonObject
        val secret = "dd".repeat(32).hexToBytes()
        assertThrows(IllegalArgumentException::class.java) {
            BoxCadence.sign("a".repeat(52), "POST", "/cadence/v1/status", value, secret, 1, ByteArray(32))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BoxCadence.sign("a".repeat(52), "POST", "https://example/cadence/v1/status", JsonObject(emptyMap()), secret, 1, ByteArray(32))
        }
    }

    @Test fun notReadyStatusKeepsTheConcreteMissingProductionCapabilities() {
        val body = "{\"v\":1,\"code\":\"not-ready\",\"ready\":false,\"server_time\":1800000000,\"current_epoch\":500000,\"earliest_start_epoch\":500002,\"missing\":[\"store-key-provider\",\"tor-i2p-carriers\"]}".toByteArray()
        val status = BoxCadence.parseStatus(200, body)
        assertFalse(status.ready)
        assertEquals(500002, status.earliestStartEpoch)
        assertEquals(listOf("store-key-provider", "tor-i2p-carriers"), status.missing)
        assertThrows(IllegalArgumentException::class.java) {
            BoxCadence.parseStatus(200, body.toString(Charsets.UTF_8).replace("500002", "499999").toByteArray())
        }
        assertThrows(IllegalStateException::class.java) {
            BoxCadence.parseStatus(403, "{\"v\":1,\"code\":\"scope\",\"server_time\":1}".toByteArray())
        }
    }

    @Test fun readyStatusUsesBothysOkCode() {
        val body = "{\"v\":1,\"code\":\"ok\",\"ready\":true,\"server_time\":1800000000,\"current_epoch\":500000,\"earliest_start_epoch\":500002,\"missing\":[]}".toByteArray()
        val status = BoxCadence.parseStatus(200, body)
        assertTrue(status.ready)
        assertEquals("ok", status.code)
        assertThrows(IllegalArgumentException::class.java) {
            BoxCadence.parseStatus(200, body.toString(Charsets.UTF_8).replace("\"ok\"", "\"ready\"").toByteArray())
        }
    }

    @Test fun receiptsAreStrictAndBounded() {
        val body = "{\"v\":1,\"code\":\"ok\",\"lease_id\":\"${"22".repeat(16)}\",\"generation\":1,\"state\":\"active\",\"server_time\":1800000000,\"start_epoch\":500002,\"end_epoch\":500004,\"queue_count\":1,\"sent_item_ids\":[\"${"ab".repeat(32)}\"],\"failed_item_ids\":[]}".toByteArray()
        val receipt = BoxCadence.parseReceipt(200, body)
        assertEquals("active", receipt.state)
        assertEquals(1, receipt.queueCount)
        assertThrows(IllegalArgumentException::class.java) {
            BoxCadence.parseReceipt(200, body.toString(Charsets.UTF_8).replace("\"failed_item_ids\":[]", "\"failed_item_ids\":[\"${"ab".repeat(32)}\"]").toByteArray())
        }
    }
}
