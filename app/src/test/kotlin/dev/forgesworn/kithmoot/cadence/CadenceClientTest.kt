package dev.forgesworn.kithmoot.cadence

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.CadenceScope
import dev.forgesworn.kithmoot.protocol.createDeviceCredential
import dev.forgesworn.kithmoot.relay.LinkJsonRequest
import dev.forgesworn.kithmoot.relay.LinkJsonResponse
import dev.forgesworn.kithmoot.relay.LinkJsonTransport
import dev.forgesworn.kithmoot.relay.LinkConsent
import dev.forgesworn.kithmoot.relay.LinkConsentState
import dev.forgesworn.kithmoot.relay.LinkConsentVault
import dev.forgesworn.kithmoot.relay.LinkPathState
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import java.util.concurrent.CompletableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.forgesworn.kithmoot.storage.RoomStorage

class CadenceClientTest {
    @Test fun statusCrossesOnlyThePinnedLinkRequestSeam() {
        val now = 1_800_000_000L
        val room = "42".repeat(32)
        val personaSecret = "ee".repeat(32).hexToBytes()
        val deviceSecret = "dd".repeat(32).hexToBytes()
        val device = dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(deviceSecret)
        val credential = createDeviceCredential(personaSecret, device, room, now + 14_400, now, ByteArray(32))
        val identity = PrimaryIdentity(LocalSigner(personaSecret), deviceSecret, credential)
        val scope = CadenceScope("a".repeat(52), room, identity.participant, device, credential, "33".repeat(16))
        val consentStorage = MemoryStorage()
        val consents = LinkConsentVault(consentStorage).apply {
            put(LinkConsent(identity.participant, room, "cc".repeat(32), "circle-main", "ws://${scope.nodeId}/events", listOf("wss://relay.example"), LinkConsentState.ACTIVE))
        }
        var received: LinkJsonRequest? = null
        val transport = LinkJsonTransport { request ->
            received = request
            CompletableFuture.completedFuture(LinkJsonResponse(
                200,
                "{\"v\":1,\"code\":\"not-ready\",\"ready\":false,\"server_time\":1800000000,\"current_epoch\":500000,\"earliest_start_epoch\":500002,\"missing\":[\"store-key-provider\",\"tor-i2p-carriers\"]}".toByteArray(),
                LinkPathState("relayed", "relay", null, ""),
            ))
        }

        val result = CadenceClient(transport, consents).status(identity.participant, scope, "11".repeat(16), identity, now).get()

        assertFalse(result.answer.ready)
        assertEquals("relayed", result.path.status)
        assertEquals("circle-main", received?.routeId)
        assertEquals("POST", received?.method)
        assertEquals("/cadence/v1/status", received?.path)
        assertTrue(received?.authorization?.startsWith("Nostr ") == true)
        assertTrue(received?.body?.toString(Charsets.UTF_8)?.contains("\"device\":\"$device\"") == true)
    }

    @Test fun withdrawnConsentRejectsALateStatusResult() {
        val now = 1_800_000_000L
        val room = "42".repeat(32)
        val personaSecret = "ee".repeat(32).hexToBytes()
        val deviceSecret = "dd".repeat(32).hexToBytes()
        val device = dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(deviceSecret)
        val credential = createDeviceCredential(personaSecret, device, room, now + 14_400, now, ByteArray(32))
        val identity = PrimaryIdentity(LocalSigner(personaSecret), deviceSecret, credential)
        val scope = CadenceScope("a".repeat(52), room, identity.participant, device, credential, "33".repeat(16))
        val consents = LinkConsentVault(MemoryStorage())
        consents.put(LinkConsent(identity.participant, room, "cc".repeat(32), "route", "ws://${scope.nodeId}/events", listOf("wss://relay.example"), LinkConsentState.ACTIVE))
        val pending = CompletableFuture<LinkJsonResponse>()
        val result = CadenceClient(LinkJsonTransport { pending }, consents)
            .status(identity.participant, scope, "11".repeat(16), identity, now)
        consents.remove(identity.participant, room, "cc".repeat(32))
        pending.complete(LinkJsonResponse(200, "{\"v\":1,\"code\":\"not-ready\",\"ready\":false,\"server_time\":1800000000,\"current_epoch\":500000,\"earliest_start_epoch\":500002,\"missing\":[\"store-key-provider\",\"tor-i2p-carriers\"]}".toByteArray(), LinkPathState("relayed", "relay", null, "")))

        val failure = org.junit.Assert.assertThrows(java.util.concurrent.ExecutionException::class.java) { result.get() }
        assertTrue(failure.cause?.message?.contains("consent changed") == true)
    }

    private class MemoryStorage : RoomStorage {
        private var value: ByteArray? = null
        override fun read(): ByteArray? = value?.copyOf()
        override fun write(value: ByteArray) { this.value = value.copyOf() }
        override fun reset() { value = null }
    }
}
