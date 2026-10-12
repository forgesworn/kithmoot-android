package dev.forgesworn.kithmoot.cadence

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.CadenceScope
import dev.forgesworn.kithmoot.protocol.CadenceLeaseOptions
import dev.forgesworn.kithmoot.protocol.toRustWireJson
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
        val scope = CadenceScope("a".repeat(52), room, room, 1, identity.participant, device, credential, "33".repeat(16))
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

    @Test fun cancellationReachesTheQueuedLinkRequest() {
        val fixture = fixture()
        val pending = CompletableFuture<LinkJsonResponse>()
        val result = CadenceClient(LinkJsonTransport { pending }, fixture.consents)
            .status(fixture.identity.participant, fixture.scope, "11".repeat(16), fixture.identity, fixture.now)
        assertTrue(result.cancel(true))
        assertTrue(pending.isCancelled)
    }

    @Test fun dispatchGuardRejectsConsentWithdrawnAfterQueueing() {
        val fixture = fixture()
        var request: LinkJsonRequest? = null
        CadenceClient(LinkJsonTransport { request = it; CompletableFuture() }, fixture.consents)
            .status(fixture.identity.participant, fixture.scope, "11".repeat(16), fixture.identity, fixture.now)
        assertTrue(request!!.stillAllowed())
        fixture.consents.remove(fixture.identity.participant, fixture.scope.room, "cc".repeat(32))
        assertFalse(request!!.stillAllowed())
    }

    @Test fun withdrawnConsentRejectsALateStatusResult() {
        val now = 1_800_000_000L
        val room = "42".repeat(32)
        val personaSecret = "ee".repeat(32).hexToBytes()
        val deviceSecret = "dd".repeat(32).hexToBytes()
        val device = dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(deviceSecret)
        val credential = createDeviceCredential(personaSecret, device, room, now + 14_400, now, ByteArray(32))
        val identity = PrimaryIdentity(LocalSigner(personaSecret), deviceSecret, credential)
        val scope = CadenceScope("a".repeat(52), room, room, 1, identity.participant, device, credential, "33".repeat(16))
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
            val code = when {
                request.path.endsWith("/queue") -> "queued"
                request.path.endsWith("/stop") -> "stopping"
                request.path.endsWith("/rekey") -> "rekeyed"
                else -> "staged"
            }
            CompletableFuture.completedFuture(LinkJsonResponse(200, receipt(queue, code), LinkPathState("direct", "route", null, "")))
        }
        val vault = CadenceLeaseVault(MemoryStorage())
        val client = CadenceClient(transport, fixture.consents)
        val options = CadenceLeaseOptions(
            fixture.scope, "11".repeat(16), "22".repeat(16), 1, 0, 500000, 500002, 500004,
            ByteArray(32) { 9 }, listOf("wss://relay-a.example", "wss://relay-b.example"), listOf("local"), fixture.now,
        )
        val staged = client.stage(fixture.identity.participant, options, fixture.identity, fixture.now, vault).get()
        assertEquals(CadenceOwnership.BOX_OWNED, staged.lease.ownership)
        assertEquals((0 until 8).toList(), vault.reservedCounters(fixture.scope.room, fixture.scope.device, 500002))
        assertEquals(staged.lease.plan.requestBody, requests.single().body.decodeToString())

        val event = Events.sign(fixture.identity.deviceSecretKey, 1460, fixture.now, listOf(listOf("d", fixture.scope.trafficRoom)), "cipher", ByteArray(32))
        val queued = client.queue(fixture.identity.participant, fixture.scope, fixture.identity, staged.lease, "44".repeat(16), event, fixture.now, vault).get()
        assertEquals(1, queued.lease.receipt?.queueCount)
        val stopped = client.stop(fixture.identity.participant, fixture.scope, fixture.identity, queued.lease, "55".repeat(16), 500002, fixture.now, vault).get()
        val rekeyed = client.rekey(fixture.identity.participant, fixture.scope, fixture.identity, stopped.lease, "66".repeat(16), 2, fixture.now, vault).get()
        assertEquals(listOf("PUT", "POST", "PUT", "PUT"), requests.map { it.method })
        assertEquals(listOf(
            "/cadence/v1/leases/${"22".repeat(16)}",
            "/cadence/v1/leases/${"22".repeat(16)}/queue",
            "/cadence/v1/leases/${"22".repeat(16)}/stop",
            "/cadence/v1/leases/${"22".repeat(16)}/rekey",
        ), requests.map { it.path })
        assertEquals("rekeyed", rekeyed.lease.receipt?.code)
        assertEquals(CadenceOwnership.BOX_OWNED, rekeyed.lease.ownership)
    }

    @Test fun guardedQueueKeepsDeadlineAndRevokesDispatchWithoutOrdinaryFallback() {
        val fixture = fixture()
        var clock = fixture.now
        var bounded: LinkJsonRequest? = null
        val pending = CompletableFuture<LinkJsonResponse>()
        var ordinaryQueues = 0
        val client = CadenceClient(LinkJsonTransport { request ->
            when {
                request.path.endsWith("/queue-guarded") -> { bounded = request; pending }
                request.path.endsWith("/queue") -> { ordinaryQueues++; error("recording used ordinary queue") }
                else -> CompletableFuture.completedFuture(LinkJsonResponse(200, receipt(0, "staged"),
                    LinkPathState("direct", "route", null, "")))
            }
        }, fixture.consents) { clock }
        val vault = CadenceLeaseVault(MemoryStorage())
        val options = CadenceLeaseOptions(fixture.scope, "11".repeat(16), "22".repeat(16), 1, 0,
            500000, 500002, 500004, ByteArray(32) { 9 },
            listOf("wss://relay-a.example", "wss://relay-b.example"), listOf("local"), fixture.now)
        val lease = client.stage(fixture.identity.participant, options, fixture.identity, fixture.now, vault).get().lease
        val event = Events.sign(fixture.identity.deviceSecretKey, 1460, fixture.now,
            listOf(listOf("d", fixture.scope.trafficRoom)), "recording ciphertext", ByteArray(32))
        val deadline = fixture.now + 60
        var allowed = true
        val result = client.queueGuarded(fixture.identity.participant, fixture.scope, fixture.identity,
            lease, "44".repeat(16), event, deadline, fixture.now, vault) { allowed }
        assertTrue(bounded!!.stillAllowed())
        val body = kotlinx.serialization.json.Json.parseToJsonElement(bounded!!.body.toString(Charsets.UTF_8)).let {
            it as kotlinx.serialization.json.JsonObject
        }
        assertEquals(setOf("queue", "publish_before"), body.keys)
        assertEquals(deadline.toString(), body.getValue("publish_before").toString())
        val queue = body.getValue("queue") as kotlinx.serialization.json.JsonObject
        assertEquals(event.toRustWireJson(), queue.getValue("event"))
        assertEquals("route", bounded!!.routeId)
        allowed = false
        assertFalse(bounded!!.stillAllowed())
        allowed = true
        clock = deadline
        assertFalse(bounded!!.stillAllowed())
        assertEquals(0, ordinaryQueues)
        assertTrue(result.cancel(true))
        assertTrue(pending.isCancelled)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            client.queueGuarded(fixture.identity.participant, fixture.scope, fixture.identity,
                lease, "55".repeat(16), event, deadline, deadline, vault) { true }
        }
    }

    @Test fun recordingCustodyAndLostWithdrawalReplyRetainOneOriginalJournalRequest() {
        val fixture = fixture()
        val journalStorage = MemoryStorage()
        val journal = RecordingNodeHandoffJournal(journalStorage) { fixture.now }.apply { recover() }
        val requests = mutableListOf<LinkJsonRequest>()
        val lostReply = CompletableFuture<LinkJsonResponse>()
        var withdrawals = 0
        val client = CadenceClient(LinkJsonTransport { request ->
            requests += request
            assertTrue(request.stillAllowed())
            when {
                request.path.endsWith("/queue-guarded") -> {
                    assertEquals(RecordingNodeHandoffState.OFFERED, journal.pending(fixture.identity.participant).single().state)
                    val recovered = RecordingNodeHandoffJournal(journalStorage) { fixture.now }.apply { recover() }
                    val entry = recovered.pending(fixture.identity.participant).single()
                    assertArrayEquals(entry.binding.queueBody.toByteArray(), request.body)
                    assertFalse(recovered.stillAllowed(entry.binding))
                    CompletableFuture.completedFuture(LinkJsonResponse(200, receipt(1, "queued"), LinkPathState("direct", "route", null, "")))
                }
                request.path.endsWith("/withdraw") -> if (withdrawals++ == 0) lostReply else
                    CompletableFuture.completedFuture(LinkJsonResponse(200, receipt(0, "withdrawn"), LinkPathState("direct", "route", null, "")))
                request.path.endsWith("/queue") -> error("recording fell back to ordinary queue")
                else -> CompletableFuture.completedFuture(LinkJsonResponse(200, receipt(0, "staged"), LinkPathState("direct", "route", null, "")))
            }
        }, fixture.consents) { fixture.now }
        val vault = CadenceLeaseVault(MemoryStorage())
        val options = CadenceLeaseOptions(fixture.scope, "11".repeat(16), "22".repeat(16), 1, 0,
            500000, 500002, 500004, ByteArray(32) { 9 },
            listOf("wss://relay-a.example", "wss://relay-b.example"), listOf("local"), fixture.now)
        val lease = client.stage(fixture.identity.participant, options, fixture.identity, fixture.now, vault).get().lease
        val event = Events.sign(fixture.identity.deviceSecretKey, 1460, fixture.now,
            listOf(listOf("d", fixture.scope.trafficRoom)), "recording ciphertext", ByteArray(32))
        val binding = RecordingNodeHandoffBinding("00000000-0000-0000-0000-000000000001", fixture.identity.participant,
            fixture.scope.nodeId, lease.plan.requestBody, "44".repeat(16), event, fixture.now + 60)
        journal.prepare(binding)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            client.queueRecording("ff".repeat(32), fixture.scope, fixture.identity, lease, binding,
                fixture.now, vault, journal) { true }
        }
        assertEquals(1, requests.size)
        val queued = client.queueRecording(fixture.identity.participant, fixture.scope, fixture.identity, lease,
            binding, fixture.now, vault, journal) { true }.get()
        assertEquals(RecordingNodeHandoffState.QUEUED, queued.handoff.state)
        assertEquals(event, queued.handoff.binding.event)
        assertEquals(1, journal.pending(fixture.identity.participant).size)
        journal.revokeRoom(fixture.scope.room)
        val failed = client.withdrawRecording(fixture.identity.participant, fixture.scope, fixture.identity,
            queued.custody.lease, binding, "66".repeat(16), fixture.now, vault, journal) { true }
        lostReply.completeExceptionally(IllegalStateException("lost withdrawal reply"))
        org.junit.Assert.assertThrows(java.util.concurrent.ExecutionException::class.java) { failed.get() }
        val restarted = RecordingNodeHandoffJournal(journalStorage) { fixture.now }.apply { recover() }
        val retained = restarted.pending(fixture.identity.participant).single()
        assertEquals(RecordingNodeHandoffState.CANCEL_PENDING, retained.state)
        val withdrawal = requireNotNull(retained.withdrawal)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            client.withdrawRecording(fixture.identity.participant, fixture.scope, fixture.identity,
                queued.custody.lease, binding, "77".repeat(16), fixture.now + 1, vault, restarted) { true }
        }
        assertEquals(3, requests.size)
        val result = client.withdrawRecording(fixture.identity.participant, fixture.scope, fixture.identity,
            queued.custody.lease, binding, withdrawal.requestId, fixture.now + 1, vault, restarted) { true }.get()
        assertEquals("withdrawn", result.lease.receipt?.code)
        val sentWithdrawals = requests.filter { it.path.endsWith("/withdraw") }
        assertEquals(listOf(withdrawal.path, withdrawal.path), sentWithdrawals.map { it.path })
        assertArrayEquals(sentWithdrawals[0].body, sentWithdrawals[1].body)
        assertTrue(restarted.pending(fixture.identity.participant).isEmpty())
    }

    @Test fun journalAdapterKeepsCustodyPendingAndRevokesOnlyTheOriginalRecording() {
        val fixture = fixture()
        val storage = MemoryStorage()
        val journal = RecordingNodeHandoffJournal(storage) { fixture.now }.apply { recover() }
        val requests = mutableListOf<LinkJsonRequest>()
        val client = CadenceClient(LinkJsonTransport { request ->
            requests += request
            assertTrue(request.stillAllowed())
            val queued = request.path.endsWith("/queue-guarded")
            if (queued) assertEquals(RecordingNodeHandoffState.OFFERED,
                RecordingNodeHandoffJournal(storage) { fixture.now }.apply { recover() }
                    .pending(fixture.identity.participant).last().state)
            check(!request.path.endsWith("/queue"))
            CompletableFuture.completedFuture(LinkJsonResponse(200,
                receipt(if (queued) 1 else 0, if (queued) "queued" else "staged"),
                LinkPathState("direct", "route", null, "")))
        }, fixture.consents) { fixture.now }
        val vault = CadenceLeaseVault(MemoryStorage())
        val options = CadenceLeaseOptions(fixture.scope, "11".repeat(16), "22".repeat(16), 1, 0,
            500000, 500002, 500004, ByteArray(32) { 9 },
            listOf("wss://relay-a.example", "wss://relay-b.example"), listOf("local"), fixture.now)
        val lease = client.stage(fixture.identity.participant, options, fixture.identity, fixture.now, vault).get().lease
        fun event(content: String) = Events.sign(fixture.identity.deviceSecretKey, 1460, fixture.now,
            listOf(listOf("d", fixture.scope.trafficRoom)), content, ByteArray(32))
        val first = event("first recording")
        val second = event("second recording")
        var relayProof: String? = null
        val revoked = mutableListOf<RecordingNodeHandoffBinding>()
        fun adapter(owner: RecordingNodeHandoffJournal) = JournalGuardedCadenceHandoff(
            fixture.identity.participant, fixture.scope, fixture.identity, client, vault, owner,
            bindingFor = { selected, original, deadline -> RecordingNodeHandoffBinding(
                "00000000-0000-0000-0000-00000000000${if (original == first) 1 else 2}",
                fixture.identity.participant, fixture.scope.nodeId, selected.plan.requestBody,
                (if (original == first) "44" else "55").repeat(16), original, deadline) },
            receivedEventConfirmsPublication = { it == relayProof }, onRevoked = revoked::add,
            now = { fixture.now })
        val handoff = adapter(journal)
        handoff.queue(lease, first, fixture.now + 60) { true }.get()
        assertEquals(RecordingNodeHandoffState.QUEUED, journal.pending(fixture.identity.participant).single().state)
        org.junit.Assert.assertThrows(IllegalStateException::class.java) { handoff.confirmed(first) }
        val restarted = RecordingNodeHandoffJournal(storage) { fixture.now }.apply { recover() }
        val recovered = adapter(restarted)
        recovered.queue(lease, first, fixture.now + 60) { true }.get()
        assertEquals(1, requests.count { it.path.endsWith("/queue-guarded") })
        recovered.queue(lease, second, fixture.now + 60) { true }.get()
        recovered.revoked(first)
        assertEquals(listOf(first), revoked.map { it.event })
        assertEquals(RecordingNodeHandoffState.CANCEL_PENDING,
            restarted.pending(fixture.identity.participant).single { it.binding.event == first }.state)
        assertEquals(RecordingNodeHandoffState.QUEUED,
            restarted.pending(fixture.identity.participant).single { it.binding.event == second }.state)
        relayProof = second.id
        recovered.confirmed(second)
        assertEquals(listOf(first), restarted.pending(fixture.identity.participant).map { it.binding.event })
        assertEquals(2, requests.count { it.path.endsWith("/queue-guarded") })
    }

    @Test fun queueDispatchTracksOwnershipAndCallerCancellation() {
        val fixture = fixture()
        var queuedRequest: LinkJsonRequest? = null
        val pending = CompletableFuture<LinkJsonResponse>()
        val transport = LinkJsonTransport { request ->
            if (request.path.endsWith("/queue")) {
                queuedRequest = request
                pending
            } else CompletableFuture.completedFuture(LinkJsonResponse(200, receipt(0, "staged"),
                LinkPathState("direct", "route", null, "")))
        }
        val vault = CadenceLeaseVault(MemoryStorage())
        val client = CadenceClient(transport, fixture.consents)
        val options = CadenceLeaseOptions(fixture.scope, "11".repeat(16), "22".repeat(16), 1, 0,
            500000, 500002, 500004, ByteArray(32) { 9 },
            listOf("wss://relay-a.example", "wss://relay-b.example"), listOf("local"), fixture.now)
        val lease = client.stage(fixture.identity.participant, options, fixture.identity, fixture.now, vault).get().lease
        val event = Events.sign(fixture.identity.deviceSecretKey, 1460, fixture.now,
            listOf(listOf("d", fixture.scope.trafficRoom)), "cipher", ByteArray(32))
        val allowed = java.util.concurrent.atomic.AtomicBoolean(true)
        val result = client.queue(fixture.identity.participant, fixture.scope, fixture.identity, lease,
            "44".repeat(16), event, fixture.now, vault, allowed::get)
        assertTrue(queuedRequest!!.stillAllowed())
        allowed.set(false)
        assertFalse(queuedRequest!!.stillAllowed())
        allowed.set(true)
        assertTrue(queuedRequest!!.stillAllowed())
        vault.accept(lease, lease.receipt!!.copy(code = "status", state = "ended"), fixture.now + 1)
        assertFalse(queuedRequest!!.stillAllowed())
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            client.queue(fixture.identity.participant, fixture.scope, fixture.identity, lease,
                "55".repeat(16), event, fixture.now + 1, vault)
        }
        assertTrue(result.cancel(true))
        assertTrue(pending.isCancelled)
    }

    @Test fun aLostLeaseResponseKeepsTheExactRequestExcludedForRetry() {
        val fixture = fixture()
        val first = CompletableFuture<LinkJsonResponse>()
        val bodies = mutableListOf<ByteArray>()
        var call = 0
        val client = CadenceClient(LinkJsonTransport { request ->
            bodies += request.body.copyOf()
            if (call++ == 0) first else CompletableFuture.completedFuture(LinkJsonResponse(200, receipt(0, "staged"), LinkPathState("relayed", "route", null, "")))
        }, fixture.consents)
        val vault = CadenceLeaseVault(MemoryStorage())
        val options = CadenceLeaseOptions(
            fixture.scope, "11".repeat(16), "22".repeat(16), 1, 0, 500000, 500002, 500004,
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
        val scope = CadenceScope("a".repeat(52), room, room, 1, identity.participant, device, credential, "33".repeat(16))
        val consents = LinkConsentVault(MemoryStorage()).apply {
            put(LinkConsent(identity.participant, room, "cc".repeat(32), "route", "ws://${scope.nodeId}/events", listOf("wss://relay.example"), LinkConsentState.ACTIVE))
        }
        return Fixture(now, identity, scope, consents)
    }

    private fun receipt(queue: Int, code: String) = "{\"v\":1,\"code\":\"$code\",\"lease_id\":\"${"22".repeat(16)}\",\"generation\":1,\"state\":\"staged\",\"server_time\":1800000000,\"start_epoch\":500002,\"end_epoch\":500004,\"queue_count\":$queue,\"sent_item_ids\":[],\"failed_item_ids\":[]}".toByteArray()

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
