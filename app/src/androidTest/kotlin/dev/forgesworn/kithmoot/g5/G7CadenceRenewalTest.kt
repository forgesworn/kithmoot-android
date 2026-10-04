package dev.forgesworn.kithmoot.g5

import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.cadence.CadenceOwnership
import dev.forgesworn.kithmoot.cadence.CadenceRenewal
import dev.forgesworn.kithmoot.cadence.CadenceSchedule
import dev.forgesworn.kithmoot.cadence.CadenceScopeKey
import dev.forgesworn.kithmoot.cadence.StoredCadenceLease
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.BothyPairing
import dev.forgesworn.kithmoot.protocol.CadenceLeaseOptions
import dev.forgesworn.kithmoot.protocol.CadenceScope
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.createDeviceCredential
import dev.forgesworn.kithmoot.relay.LinkConsent
import dev.forgesworn.kithmoot.relay.LinkConsentState
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-01 lab cases C-L01 to C-L05 against Bothy's cadence fixture, over the
 * real Link route. Each action makes the calls the room's Renew, Stop and
 * Retry make: [CadenceSchedule] chooses, [dev.forgesworn.kithmoot.cadence.CadenceClient]
 * sends. "Now" is the box scheduler's epoch, which the fixture compresses;
 * Bothy still admits on wall-clock time.
 *
 * Chain A runs on device slot 0 in the room's traffic room, chain B on slot 1
 * in a second traffic room, so neither the phone's schedule nor Bothy's scope
 * mixes them.
 */
class G7CadenceRenewalTest {
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val control get() = requireNotNull(arguments.getString("fixture_control")).removeSuffix("/")
    private val action get() = requireNotNull(arguments.getString("g7_action"))
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KithMootApplication

    @Test fun runsRequestedRenewalCase() {
        when (action) {
            "renewal-start" -> start()
            "renew-lost-reply" -> renewWithLostReply()
            "renew-b" -> renewChainB()
            "stop-b" -> stopChainB()
            "late-rekey-a" -> refuseLateRenewalThenRekey()
            else -> throw AssertionError("unknown G7 renewal action")
        }
    }

    /** Pair, consent, and stage A1 and B1. B1 carries one real message. */
    private fun start() {
        val now = epochSeconds()
        val pairing = parsePairing(post("pairing").getValue("uri").jsonPrimitive.content, now)
        val route = app.linkEngine.pair(pairing.card, pairing.pairingSecret, pairing.expiresAt).get(120, TimeUnit.SECONDS)
        val ready = get("ready")
        val nodeId = ready.getValue("link_node_id").jsonPrimitive.content
        // A provisional credential asks for the start; the journey's credential then expires at s + 5.
        val provisional = identity(now + 43_200)
        app.linkConsents.put(consent(provisional, nodeId, route.routeId, ready.getValue("claimed_by").jsonPrimitive.content))
        val status = app.cadenceClient.status(provisional.participant, scope(provisional, nodeId, A_TRAFFIC, 1), "60".repeat(16), provisional, now)
            .get(120, TimeUnit.SECONDS).answer
        assertTrue(status.missing.joinToString(","), status.ready)
        val s = status.earliestStartEpoch
        val credentialExpires = (s + 5) * 3600
        put("g7-renewal-setup", buildJsonObject {
            put("startEpoch", s); put("credentialExpires", credentialExpires); put("nodeId", nodeId)
        })
        val who = identity(credentialExpires)

        val a1 = app.cadenceClient.stage(who.participant, options(who, nodeId, A_TRAFFIC, 0, A_LEASE, 1, status.currentEpoch, s, s + 3),
            who, epochSeconds(), app.cadenceLeases).get(120, TimeUnit.SECONDS).lease
        assertEquals("staged", a1.receipt?.state)
        val b1 = app.cadenceClient.stage(who.participant, options(who, nodeId, B_TRAFFIC, 1, B_LEASE, 1, status.currentEpoch, s + 2, s + 4),
            who, epochSeconds(), app.cadenceLeases).get(120, TimeUnit.SECONDS).lease
        val b1Queued = app.cadenceClient.queue(who.participant, scope(who, nodeId, B_TRAFFIC, 1), who, b1, "61".repeat(16),
            quietEvent(who, B_TRAFFIC, 1), epochSeconds(), app.cadenceLeases).get(120, TimeUnit.SECONDS).lease
        assertEquals(1, b1Queued.receipt?.queueCount)
        put("g7-renewal-a", buildJsonObject { put("leaseId", A_LEASE); put("generation", 1); put("startEpoch", s) })
        put("g7-renewal-b", buildJsonObject { put("leaseId", B_LEASE); put("generation", 1); put("startEpoch", s + 2) })
    }

    /**
     * C-L03. The renewal reaches Bothy but its reply is lost: the room's
     * consent changes while the request is in flight, so the phone refuses the
     * late answer and keeps A2's counters excluded. Retry resolves it.
     */
    private fun renewWithLostReply() {
        val setup = setup()
        val who = identity(setup.credentialExpires)
        val key = CadenceScopeKey(setup.nodeId, A_TRAFFIC, 1)
        val epoch = boxEpoch()
        val renewal = CadenceSchedule.renewal(app.cadenceLeases.all(ROOM, DEVICE), key, epoch, epoch + 2,
            setup.credentialExpires / 3600, GRANT_CAP)
        check(renewal is CadenceRenewal.Ready) { "renewal refused: $renewal" }
        assertEquals(setup.s + 3, renewal.startEpoch)
        assertEquals(setup.s + 5, renewal.endEpoch)
        val before = app.cadenceLeases.reservedCounters(ROOM, DEVICE, renewal.startEpoch)

        val consent = app.linkConsents.all().single { it.roomId == ROOM }
        val late = app.cadenceClient.stage(who.participant,
            options(who, setup.nodeId, A_TRAFFIC, 0, renewal.leaseId, renewal.generation, epoch, renewal.startEpoch, renewal.endEpoch),
            who, epochSeconds(), app.cadenceLeases)
        app.linkConsents.remove(consent.accountPubkey, consent.roomId, consent.bothyNodeId)
        val lost = assertThrows(ExecutionException::class.java) { late.get(120, TimeUnit.SECONDS) }
        assertEquals("cadence consent changed while the request was in flight", lost.cause?.message)
        app.linkConsents.put(consent)

        val unresolved = lease(A_LEASE, renewal.generation)
        assertEquals(CadenceOwnership.CLIENT_EXCLUDED, unresolved.ownership)
        val excluded = app.cadenceLeases.reservedCounters(ROOM, DEVICE, renewal.startEpoch)
        val held = boxLease(A_LEASE, renewal.generation)
        // The panel offers Retry, not Renew, while the reply is unknown.
        val offeredWhileUnknown = CadenceSchedule.renewable(app.cadenceLeases.all(ROOM, DEVICE), key, epoch)

        // Retry is the room's resolve step: the exact retained body again.
        val resolved = app.cadenceClient.retryStage(who.participant, scope(who, setup.nodeId, A_TRAFFIC, 1), who, unresolved,
            epochSeconds(), app.cadenceLeases).get(120, TimeUnit.SECONDS).lease
        val after = app.cadenceLeases.reservedCounters(ROOM, DEVICE, renewal.startEpoch)
        put("g7-renewal-a2", buildJsonObject {
            put("leaseId", A_LEASE); put("generation", renewal.generation); put("startEpoch", renewal.startEpoch)
        })
        put("g7-renewal-lost-reply", buildJsonObject {
            put("boxEpoch", epoch)
            put("boxHeldBeforeRetry", held.getValue("status").jsonPrimitive.long == 200L && held.getValue("state").jsonPrimitive.content == "staged")
            put("excludedWhileUnknown", unresolved.ownership == CadenceOwnership.CLIENT_EXCLUDED && (0 until 8).all { it in excluded } && (0 until 8).none { it in before })
            put("renewOfferedWhileUnknown", offeredWhileUnknown)
            put("resolvedByRetry", resolved.ownership == CadenceOwnership.BOX_OWNED && resolved.receipt?.state == "staged")
            put("countersKept", after == excluded)
            put("renewalGeneration", renewal.generation)
        })
    }

    /** Renew chain B as the room does, and queue one real message in the renewal. */
    private fun renewChainB() {
        val setup = setup()
        val who = identity(setup.credentialExpires)
        val key = CadenceScopeKey(setup.nodeId, B_TRAFFIC, 1)
        val epoch = boxEpoch()
        val renewal = CadenceSchedule.renewal(app.cadenceLeases.all(ROOM, DEVICE), key, epoch, epoch + 2,
            setup.credentialExpires / 3600, GRANT_CAP)
        check(renewal is CadenceRenewal.Ready) { "renewal refused: $renewal" }
        assertEquals(setup.s + 4, renewal.startEpoch)
        val b2 = app.cadenceClient.stage(who.participant,
            options(who, setup.nodeId, B_TRAFFIC, 1, renewal.leaseId, renewal.generation, epoch, renewal.startEpoch, renewal.endEpoch),
            who, epochSeconds(), app.cadenceLeases).get(120, TimeUnit.SECONDS).lease
        assertEquals("staged", b2.receipt?.state)
        val queued = app.cadenceClient.queue(who.participant, scope(who, setup.nodeId, B_TRAFFIC, 1), who, b2, "62".repeat(16),
            quietEvent(who, B_TRAFFIC, 2), epochSeconds(), app.cadenceLeases).get(120, TimeUnit.SECONDS).lease
        assertEquals(1, queued.receipt?.queueCount)
        put("g7-renewal-b2", buildJsonObject {
            put("leaseId", B_LEASE); put("generation", renewal.generation); put("startEpoch", renewal.startEpoch)
            put("endEpoch", renewal.endEpoch)
        })
    }

    /** C-L02. Stop gives the running lease a boundary two epochs on and the staged renewal its own start. */
    private fun stopChainB() {
        val setup = setup()
        val who = identity(setup.credentialExpires)
        val key = CadenceScopeKey(setup.nodeId, B_TRAFFIC, 1)
        val epoch = boxEpoch()
        val targets = CadenceSchedule.stopTargets(app.cadenceLeases.all(ROOM, DEVICE), key, epoch)
        val stopped = targets.map { (lease, boundary) ->
            app.cadenceClient.stop(who.participant, scope(who, setup.nodeId, B_TRAFFIC, 1), who, lease, "63".repeat(15) + "%02x".format(lease.plan.generation),
                boundary, epochSeconds(), app.cadenceLeases).get(120, TimeUnit.SECONDS).lease to boundary
        }
        put("g7-renewal-stop", buildJsonObject {
            put("boxEpoch", epoch)
            put("targets", stopped.size)
            stopped.forEachIndexed { index, (lease, boundary) ->
                put("generation$index", lease.plan.generation); put("boundary$index", boundary)
                put("code$index", lease.receipt?.code)
            }
        })
    }

    /**
     * C-L05, then C-L04. Within two epochs of A2's end Renew is refused. Then
     * messages are queued in A2 and the room key moves on: the rekey targets
     * the lease covering this epoch, and Bothy returns what it had not sent.
     */
    private fun refuseLateRenewalThenRekey() {
        val setup = setup()
        val who = identity(setup.credentialExpires)
        val key = CadenceScopeKey(setup.nodeId, A_TRAFFIC, 1)
        val epoch = boxEpoch()
        val late = CadenceSchedule.renewal(app.cadenceLeases.all(ROOM, DEVICE), key, epoch, epoch + 2,
            setup.credentialExpires / 3600, GRANT_CAP)
        val offered = CadenceSchedule.renewable(app.cadenceLeases.all(ROOM, DEVICE), key, epoch)

        val target = requireNotNull(CadenceSchedule.primary(app.cadenceLeases.all(ROOM, DEVICE), key, epoch))
        val aScope = scope(who, setup.nodeId, A_TRAFFIC, 1)
        var current = target
        val queued = (0 until 3).map { index ->
            val event = quietEvent(who, A_TRAFFIC, 10 + index)
            current = app.cadenceClient.queue(who.participant, aScope, who, current, "64".repeat(15) + "%02x".format(index),
                event, epochSeconds(), app.cadenceLeases).get(120, TimeUnit.SECONDS).lease
            event.id
        }
        val rekeyed = app.cadenceClient.rekey(who.participant, aScope, who, current, "65".repeat(16), 2,
            epochSeconds(), app.cadenceLeases).get(120, TimeUnit.SECONDS).lease
        val failed = rekeyed.receipt?.failedItemIds.orEmpty()
        val sent = rekeyed.receipt?.sentItemIds.orEmpty()
        put("g7-renewal-late-rekey", buildJsonObject {
            put("boxEpoch", epoch)
            put("lateRefused", late is CadenceRenewal.Refused)
            put("lateReason", (late as? CadenceRenewal.Refused)?.reason?.substringBefore('.'))
            put("renewOffered", offered)
            put("targetGeneration", target.plan.generation)
            put("targetStart", target.plan.startEpoch)
            put("rekeyCode", rekeyed.receipt?.code); put("rekeyState", rekeyed.receipt?.state)
            put("queued", queued.size)
            put("returned", failed.count { it in queued })
            put("sentOfQueued", sent.count { it in queued })
            put("everyQueuedAccounted", queued.all { it in failed || it in sent })
            put("nothingElseReturned", failed.all { it in queued })
        })
    }

    private data class Setup(val s: Long, val credentialExpires: Long, val nodeId: String)

    private fun setup() = get("journey/g7-renewal-setup").let {
        Setup(it.getValue("startEpoch").jsonPrimitive.long, it.getValue("credentialExpires").jsonPrimitive.long,
            it.getValue("nodeId").jsonPrimitive.content)
    }

    private fun boxEpoch() = get("clock").getValue("box_epoch").jsonPrimitive.long

    private fun boxLease(leaseId: String, generation: Long) = get("phone/lease/$leaseId/$generation").getValue("status").jsonObject

    private fun lease(leaseId: String, generation: Long): StoredCadenceLease =
        app.cadenceLeases.all(ROOM, DEVICE).single { it.plan.leaseId == leaseId && it.plan.generation == generation }

    private fun identity(credentialExpires: Long): PrimaryIdentity {
        val persona = PERSONA_SECRET.hexToBytes()
        return PrimaryIdentity(
            LocalSigner(persona),
            DEVICE_SECRET.hexToBytes(),
            createDeviceCredential(persona, DEVICE, ROOM, credentialExpires, epochSeconds(), ByteArray(32)),
        )
    }

    private fun scope(identity: PrimaryIdentity, nodeId: String, trafficRoom: String, roomGeneration: Long) = CadenceScope(
        nodeId, ROOM, trafficRoom, roomGeneration,
        identity.participant, identity.devicePubkey, identity.credential, GRANT_ID,
    )

    private fun options(
        identity: PrimaryIdentity,
        nodeId: String,
        trafficRoom: String,
        deviceSlot: Int,
        leaseId: String,
        generation: Long,
        currentEpoch: Long,
        start: Long,
        end: Long,
    ) = CadenceLeaseOptions(
        scope(identity, nodeId, trafficRoom, 1), randomId(), leaseId, generation, deviceSlot, currentEpoch, start, end,
        ByteArray(32) { 1 }, fixtureRelays(), listOf("local"), epochSeconds(),
    )

    private fun quietEvent(identity: PrimaryIdentity, trafficRoom: String, seed: Int): NostrEvent = Events.sign(
        identity.deviceSecretKey, 1460, epochSeconds(), listOf(listOf("d", trafficRoom)),
        "renewal fixture quiet ciphertext", ByteArray(32) { seed.toByte() },
    )

    private fun consent(identity: PrimaryIdentity, nodeId: String, routeId: String, bothy: String) = LinkConsent(
        identity.participant, ROOM, bothy, routeId, "ws://$nodeId/events",
        listOf(get("ready").getValue("introduction_relay_url").jsonPrimitive.content), LinkConsentState.ACTIVE,
    )

    private fun fixtureRelays(): List<String> {
        val relays = get("ready").getValue("routes").jsonArray.map { it.jsonPrimitive.content }
        require(relays.size == 2 && relays.distinct().size == 2 && relays.all { relay ->
            val url = URI(relay)
            url.scheme == "wss" && !url.host.isNullOrBlank() && url.port in 1..65535
        }) { "fixture must supply two distinct WebPKI relay URLs" }
        return relays
    }

    private fun parsePairing(uri: String, now: Long): NativePairing = try {
        BothyPairing.parse(uri, now).let { NativePairing(it.card, it.pairingSecret, it.expiresAt) }
    } catch (error: IllegalArgumentException) {
        val root = Json.parseToJsonElement(Base64.getUrlDecoder().decode(uri.removePrefix("bothy:")).decodeToString()).jsonObject
        val card = Base64.getDecoder().decode(root.getValue("card").jsonPrimitive.content)
        require(card.decodeToString().contains("ws://127.0.0.1:")) { throw error }
        NativePairing(card, root.getValue("secret").jsonPrimitive.content.hexToBytes(), root.getValue("exp").jsonPrimitive.content.toLong())
    }

    private fun get(path: String) = request(path, "GET")
    private fun post(path: String) = request(path, "POST")
    private fun request(path: String, method: String) = (URL("$control/$path").openConnection() as HttpURLConnection).let { connection ->
        try {
            connection.requestMethod = method; connection.connectTimeout = 10_000; connection.readTimeout = 120_000
            require(connection.responseCode in 200..299) { "fixture control rejected $path" }
            Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
        } finally { connection.disconnect() }
    }

    private fun put(key: String, value: JsonObject) {
        val connection = URL("$control/journey/$key").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            connection.outputStream.bufferedWriter().use { it.write(value.toString()) }
            require(connection.responseCode == HttpURLConnection.HTTP_NO_CONTENT)
        } finally { connection.disconnect() }
    }

    private fun randomId() = java.util.UUID.randomUUID().toString().replace("-", "")
    private fun epochSeconds() = System.currentTimeMillis() / 1000
    private class NativePairing(val card: ByteArray, val pairingSecret: ByteArray, val expiresAt: Long)

    private companion object {
        const val PERSONA_SECRET = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val DEVICE_SECRET = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        const val DEVICE = "ed83704c95d829046f1ac27806211132102c34e9ac7ffa1b71110658e5b9d1bd"
        const val ROOM = "4242424242424242424242424242424242424242424242424242424242424242"
        const val GRANT_ID = "33333333333333333333333333333333"
        const val A_TRAFFIC = ROOM
        const val B_TRAFFIC = "4444444444444444444444444444444444444444444444444444444444444444"
        const val A_LEASE = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"
        const val B_LEASE = "b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1b1"

        /** The fixture's circle grant runs for a week, so the credential is the cap that binds. */
        const val GRANT_CAP = Long.MAX_VALUE
    }
}
