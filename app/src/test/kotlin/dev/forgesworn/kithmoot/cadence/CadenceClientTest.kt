package dev.forgesworn.kithmoot.cadence

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.CadenceScope
import dev.forgesworn.kithmoot.protocol.CadenceLeaseOptions
import dev.forgesworn.kithmoot.protocol.Events
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
import org.junit.Assert.assertArrayEquals
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

    @Test fun leaseQueueAndStopStayOnThePinnedRouteAndAdvanceDurableOwnership() {
        val fixture = fixture()
        val requests = mutableListOf<LinkJsonRequest>()
        val transport = LinkJsonTransport { request ->
            requests += request
            val queue = if (request.path.endsWith("/queue")) 1 else 0
            CompletableFuture.completedFuture(LinkJsonResponse(200, receipt(queue), LinkPathState("direct", "route", null, "")))
        }
        val vault = CadenceLeaseVault(MemoryStorage())
        val client = CadenceClient(transport, fixture.consents)
        val options = CadenceLeaseOptions(
            fixture.scope, "11".repeat(16), "22".repeat(16), 1, 1, 0, 500000, 500002, 500004,
            ByteArray(32) { 9 }, listOf("wss://relay-a.example", "wss://relay-b.example"), listOf("local"), fixture.now,
        )
        val staged = client.stage(fixture.identity.participant, options, fixture.identity, fixture.now, vault).get()
        assertEquals(CadenceOwnership.BOX_OWNED, staged.lease.ownership)
        assertEquals((0 until 8).toList(), vault.reservedCounters(fixture.scope.room, fixture.scope.device, 500002))
        assertEquals(staged.lease.plan.requestBody, requests.single().body.decodeToString())

        val event = Events.sign(fixture.identity.deviceSecretKey, 1460, fixture.now, listOf(listOf("d", fixture.scope.room)), "cipher", ByteArray(32))
        val queued = client.queue(fixture.identity.participant, fixture.scope, fixture.identity, staged.lease, "44".repeat(16), event, fixture.now, vault).get()
        assertEquals(1, queued.lease.receipt?.queueCount)
        val stopped = client.stop(fixture.identity.participant, fixture.scope, fixture.identity, queued.lease, "55".repeat(16), 500002, fixture.now, vault).get()
        assertEquals(listOf("PUT", "POST", "PUT"), requests.map { it.method })
        assertEquals(listOf(
            "/cadence/v1/leases/${"22".repeat(16)}",
            "/cadence/v1/leases/${"22".repeat(16)}/queue",
            "/cadence/v1/leases/${"22".repeat(16)}/stop",
        ), requests.map { it.path })
        assertEquals(CadenceOwnership.BOX_OWNED, stopped.lease.ownership)
    }

    @Test fun aLostLeaseResponseKeepsTheExactRequestExcludedForRetry() {
        val fixture = fixture()
        val first = CompletableFuture<LinkJsonResponse>()
        val bodies = mutableListOf<ByteArray>()
        var call = 0
        val client = CadenceClient(LinkJsonTransport { request ->
            bodies += request.body.copyOf()
            if (call++ == 0) first else CompletableFuture.completedFuture(LinkJsonResponse(200, receipt(0), LinkPathState("relayed", "route", null, "")))
        }, fixture.consents)
        val vault = CadenceLeaseVault(MemoryStorage())
        val options = CadenceLeaseOptions(
            fixture.scope, "11".repeat(16), "22".repeat(16), 1, 1, 0, 500000, 500002, 500004,
            ByteArray(32) { 9 }, listOf("wss://relay-a.example", "wss://relay-b.example"), listOf("local"), fixture.now,
        )
        val pending = client.stage(fixture.identity.participant, options, fixture.identity, fixture.now, vault)
        val excluded = vault.all().single()
        assertEquals(CadenceOwnership.CLIENT_EXCLUDED, excluded.ownership)
        first.completeExceptionally(IllegalStateException("lost response"))
        org.junit.Assert.assertThrows(java.util.concurrent.ExecutionException::class.java) { pending.get() }
        val recovered = client.retryStage(fixture.identity.participant, fixture.scope, fixture.identity, excluded, fixture.now + 1, vault).get()
        assertArrayEquals(bodies[0], bodies[1])
        assertEquals(CadenceOwnership.BOX_OWNED, recovered.lease.ownership)
    }

    private fun fixture(): Fixture {
        val now = 1_800_000_000L
        val room = "42".repeat(32)
        val personaSecret = "ee".repeat(32).hexToBytes()
        val deviceSecret = "dd".repeat(32).hexToBytes()
        val device = dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(deviceSecret)
        val credential = createDeviceCredential(personaSecret, device, room, now + 14_400, now, ByteArray(32))
        val identity = PrimaryIdentity(LocalSigner(personaSecret), deviceSecret, credential)
        val scope = CadenceScope("a".repeat(52), room, identity.participant, device, credential, "33".repeat(16))
        val consents = LinkConsentVault(MemoryStorage()).apply {
            put(LinkConsent(identity.participant, room, "cc".repeat(32), "route", "ws://${scope.nodeId}/events", listOf("wss://relay.example"), LinkConsentState.ACTIVE))
        }
        return Fixture(now, identity, scope, consents)
    }

    private fun receipt(queue: Int) = "{\"v\":1,\"code\":\"ok\",\"lease_id\":\"${"22".repeat(16)}\",\"generation\":1,\"state\":\"staged\",\"server_time\":1800000000,\"start_epoch\":500002,\"end_epoch\":500004,\"queue_count\":$queue,\"sent_item_ids\":[],\"failed_item_ids\":[]}".toByteArray()

    private data class Fixture(
        val now: Long,
        val identity: PrimaryIdentity,
        val scope: CadenceScope,
        val consents: LinkConsentVault,
    )

    private class MemoryStorage : RoomStorage {
        private var value: ByteArray? = null
        override fun read(): ByteArray? = value?.copyOf()
        override fun write(value: ByteArray) { this.value = value.copyOf() }
        override fun reset() { value = null }
    }
}
