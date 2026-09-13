package dev.forgesworn.kithmoot.g5

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.cadence.CadenceOwnership
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.BothyPairing
import dev.forgesworn.kithmoot.protocol.BoxCadence
import dev.forgesworn.kithmoot.protocol.CadenceLeaseOptions
import dev.forgesworn.kithmoot.protocol.CadenceScope
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.createDeviceCredential
import dev.forgesworn.kithmoot.relay.LinkConsent
import dev.forgesworn.kithmoot.relay.LinkConsentState
import dev.forgesworn.kithmoot.relay.LinkJsonRequest
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real generated JNI binding -> Link session -> Bothy's fixture-only ordinary cadence router. */
class G7CadenceStatusTest {
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val control get() = requireNotNull(arguments.getString("fixture_control")).removeSuffix("/")
    private val action get() = requireNotNull(arguments.getString("g7_action"))
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KithMootApplication

    @Test fun runsRequestedCadenceProof() {
        when (action) {
            "probe" -> pairAndRunSuccessorCutover()
            "restart-check" -> reconnectAndVerifySuccessorCutover()
            else -> throw AssertionError("unknown G7 cadence action")
        }
    }

    private fun pairAndRunSuccessorCutover() {
        val now = epochSeconds()
        val pairing = parsePairing(post("pairing").getValue("uri").jsonPrimitive.content, now)
        val changedCard = pairing.card.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFutureRefused(app.linkEngine.pair(changedCard, pairing.pairingSecret.copyOf(), pairing.expiresAt))
        val route = app.linkEngine.pair(pairing.card, pairing.pairingSecret, pairing.expiresAt).get(120, TimeUnit.SECONDS)
        val ready = get("ready")
        val nodeId = ready.getValue("link_node_id").jsonPrimitive.content
        val identity = identity(now)
        val scope = scope(identity, nodeId)
        val body = BoxCadence.statusBody(scope, "44".repeat(16), now)
        val signed = BoxCadence.sign(nodeId, "POST", "/cadence/v1/status", body, identity.deviceSecretKey, now)
        val request = LinkJsonRequest(route.routeId, signed.method, signed.path, signed.authorization, signed.body)

        assertFutureRefused(app.linkEngine.request(request.copy(routeId = "route-absent")))
        assertFutureRefused(app.linkEngine.request(request.copy(method = "GET")))
        assertFutureRefused(app.linkEngine.request(request.copy(path = "/events")))
        assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, app.linkEngine.request(
            request.copy(body = request.body.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }),
        ).get(120, TimeUnit.SECONDS).status)
        assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, app.linkEngine.request(
            request.copy(authorization = changedSignature(request.authorization)),
        ).get(120, TimeUnit.SECONDS).status)
        assertFalse(Class.forName("dev.forgesworn.link.ffi.LinkHttpRequest").methods.any { it.name.contains("host", ignoreCase = true) })

        val consent = consent(identity, nodeId, route.routeId, ready.getValue("claimed_by").jsonPrimitive.content)
        app.linkConsents.put(consent)

        val late = app.cadenceClient.status(identity.participant, scope, REQUEST_ID, identity, now)
        app.linkConsents.remove(consent.accountPubkey, consent.roomId, consent.bothyNodeId)
        val refusal = assertThrows(ExecutionException::class.java) { late.get(120, TimeUnit.SECONDS) }
        assertEquals("cadence consent changed while the request was in flight", refusal.cause?.message)

        app.linkConsents.put(consent)
        val result = app.cadenceClient.status(identity.participant, scope, REQUEST_ID, identity, epochSeconds()).get(120, TimeUnit.SECONDS)
        val status = result.answer
        assertTrue(status.ready)
        assertTrue(status.missing.isEmpty())

        val start = status.earliestStartEpoch
        val options = leaseOptions(scope, REQUEST_ID, LEASE_ID, status.currentEpoch, start, start + 1, now, 1)
        val staged = app.cadenceClient.stage(identity.participant, options, identity, epochSeconds(), app.cadenceLeases)
            .get(120, TimeUnit.SECONDS).lease
        assertEquals(CadenceOwnership.BOX_OWNED, staged.ownership)
        assertEquals("staged", staged.receipt?.code)
        assertEquals("staged", staged.receipt?.state)
        assertEquals((0 until 8).toList(), app.cadenceLeases.reservedCounters(ROOM, DEVICE, start))

        val queuedEvent = Events.sign(
            identity.deviceSecretKey, 1460, epochSeconds(), listOf(listOf("d", ROOM)),
            "fixture quiet ciphertext", ByteArray(32) { 7 },
        )
        val queued = app.cadenceClient.queue(
            identity.participant, scope, identity, staged, QUEUE_REQUEST_ID, queuedEvent,
            epochSeconds(), app.cadenceLeases,
        ).get(120, TimeUnit.SECONDS).lease
        assertEquals("queued", queued.receipt?.code)
        assertEquals(1, queued.receipt?.queueCount)

        val rekeyed = app.cadenceClient.rekey(
            identity.participant, scope, identity, queued, REKEY_REQUEST_ID, 3,
            epochSeconds(), app.cadenceLeases,
        ).get(120, TimeUnit.SECONDS).lease
        assertEquals("rekeyed", rekeyed.receipt?.code)
        assertEquals("cover", rekeyed.receipt?.state)
        assertEquals(0, rekeyed.receipt?.queueCount)
        assertEquals(listOf(queuedEvent.id), rekeyed.receipt?.failedItemIds)
        assertFutureRefused(app.cadenceClient.queue(
            identity.participant, scope, identity, rekeyed, OLD_QUEUE_REQUEST_ID,
            Events.sign(identity.deviceSecretKey, 1460, epochSeconds(), listOf(listOf("d", ROOM)), "late", ByteArray(32) { 8 }),
            epochSeconds(), app.cadenceLeases,
        ))
        put("g7-cadence-probe", buildJsonObject {
            put("authenticated", true); put("ready", status.ready); put("missing", status.missing.joinToString(","))
            put("linkPath", result.path.status); put("consentWithdrawalRefusedLateResult", true); put("countersExcluded", true)
            put("leaseStaged", true); put("messageQueued", true); put("rekeyedBeforeStart", true)
            put("queuedMessageFailed", true); put("oldGenerationQueueRefused", true)
            put("changedNodeCardRefused", true); put("unknownRouteRefused", true); put("changedMethodRefused", true)
            put("changedPathRefused", true); put("changedPayloadRefused", true); put("changedDeviceSignatureRefused", true)
            put("hostPinnedByBridge", true)
        })
    }

    private fun reconnectAndVerifySuccessorCutover() {
        val stored = app.cadenceLeases.all(ROOM, DEVICE).single()
        assertEquals(CadenceOwnership.BOX_OWNED, stored.ownership)
        assertEquals("rekeyed", stored.receipt?.code)
        assertEquals("cover", stored.receipt?.state)
        assertEquals(1, stored.receipt?.failedItemIds?.size)
        assertEquals((0 until 8).toList(), app.cadenceLeases.reservedCounters(ROOM, DEVICE, stored.plan.startEpoch))
        val consent = app.linkConsents.all().single { it.roomId == ROOM }
        assertTrue(app.linkEngine.routeIds().contains(consent.routeId))
        val now = epochSeconds()
        val identity = identity(now)
        val nodeId = consent.canonicalUrl.removePrefix("ws://").removeSuffix("/events")
        val status = app.cadenceClient.status(identity.participant, scope(identity, nodeId), "66".repeat(16), identity, epochSeconds()).get(120, TimeUnit.SECONDS)
        assertTrue(status.answer.ready)
        val retained = app.cadenceClient.leaseStatus(
            identity.participant, scope(identity, nodeId), identity, stored, STATUS_REQUEST_ID,
            epochSeconds(), app.cadenceLeases,
        ).get(120, TimeUnit.SECONDS).lease
        assertEquals("status", retained.receipt?.code)
        assertEquals("cover", retained.receipt?.state)
        assertEquals(stored.receipt?.failedItemIds, retained.receipt?.failedItemIds)

        val lowerScope = scope(identity, nodeId, LOWER_TRAFFIC_ROOM, 2)
        val lowerStart = maxOf(status.answer.earliestStartEpoch, stored.plan.endEpoch)
        assertFutureRefused(app.cadenceClient.stage(
            identity.participant,
            leaseOptions(
                lowerScope, LOWER_REQUEST_ID, LOWER_LEASE_ID, status.answer.currentEpoch,
                lowerStart, lowerStart + 1, now, 2,
            ),
            identity, epochSeconds(), app.cadenceLeases,
        ))
        val lower = app.cadenceLeases.all(ROOM, DEVICE).single { it.plan.leaseId == LOWER_LEASE_ID }
        assertEquals(CadenceOwnership.CLIENT_EXCLUDED, lower.ownership)
        put("g7-cadence-restart", buildJsonObject {
            put("routeRestored", true); put("rekeyReceiptRetained", true); put("countersStillExcluded", true)
            put("authenticatedStatusAfterRestart", true); put("oldLeaseCoverAfterRestart", true)
            put("queuedFailureRetained", true); put("lowerGenerationRefused", true); put("linkPath", status.path.status)
        })
    }

    private fun identity(now: Long): PrimaryIdentity {
        val persona = PERSONA_SECRET.hexToBytes()
        val device = DEVICE_SECRET.hexToBytes()
        return PrimaryIdentity(
            LocalSigner(persona),
            device,
            createDeviceCredential(persona, DEVICE, ROOM, now + 14_400, now, ByteArray(32)),
        )
    }

    private fun scope(
        identity: PrimaryIdentity,
        nodeId: String,
        trafficRoom: String = ROOM,
        roomGeneration: Long = 1,
    ) = CadenceScope(
        nodeId, ROOM, trafficRoom, roomGeneration,
        identity.participant, identity.devicePubkey, identity.credential, GRANT_ID,
    )

    private fun consent(identity: PrimaryIdentity, nodeId: String, routeId: String, bothy: String) = LinkConsent(
        identity.participant, ROOM, bothy, routeId, "ws://$nodeId/events",
        listOf(get("ready").getValue("introduction_relay_url").jsonPrimitive.content), LinkConsentState.ACTIVE,
    )

    private fun leaseOptions(
        scope: CadenceScope,
        requestId: String,
        leaseId: String,
        currentEpoch: Long,
        start: Long,
        end: Long,
        now: Long,
        generation: Long,
    ) = CadenceLeaseOptions(
        scope, requestId, leaseId, generation, 0, currentEpoch, start, end,
        ByteArray(32) { scope.roomGeneration.toByte() }, FIXTURE_RELAYS, listOf("local"), now,
    )

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

    private fun put(key: String, value: kotlinx.serialization.json.JsonObject) {
        val connection = URL("$control/journey/$key").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            connection.outputStream.bufferedWriter().use { it.write(value.toString()) }
            require(connection.responseCode == HttpURLConnection.HTTP_NO_CONTENT)
        } finally { connection.disconnect() }
    }

    private fun assertFutureRefused(future: java.util.concurrent.CompletableFuture<*>) {
        assertThrows(ExecutionException::class.java) { future.get(120, TimeUnit.SECONDS) }
    }

    private fun changedSignature(authorization: String): String {
        val raw = Base64.getDecoder().decode(authorization.removePrefix("Nostr ")).decodeToString()
        val signature = Json.parseToJsonElement(raw).jsonObject.getValue("sig").jsonPrimitive.content
        val changed = (if (signature[0] == '0') "1" else "0") + signature.drop(1)
        return "Nostr " + Base64.getEncoder().encodeToString(raw.replaceFirst(signature, changed).toByteArray())
    }

    private fun epochSeconds() = System.currentTimeMillis() / 1000
    private data class NativePairing(val card: ByteArray, val pairingSecret: ByteArray, val expiresAt: Long)

    private companion object {
        const val PERSONA_SECRET = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val DEVICE_SECRET = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        const val DEVICE = "ed83704c95d829046f1ac27806211132102c34e9ac7ffa1b71110658e5b9d1bd"
        const val ROOM = "4242424242424242424242424242424242424242424242424242424242424242"
        const val GRANT_ID = "33333333333333333333333333333333"
        const val REQUEST_ID = "11111111111111111111111111111111"
        const val LEASE_ID = "22222222222222222222222222222222"
        const val QUEUE_REQUEST_ID = "44444444444444444444444444444444"
        const val REKEY_REQUEST_ID = "55555555555555555555555555555555"
        const val OLD_QUEUE_REQUEST_ID = "77777777777777777777777777777777"
        const val STATUS_REQUEST_ID = "88888888888888888888888888888888"
        const val LOWER_REQUEST_ID = "99999999999999999999999999999999"
        const val LOWER_LEASE_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val LOWER_TRAFFIC_ROOM = "4343434343434343434343434343434343434343434343434343434343434343"
        val FIXTURE_RELAYS = listOf("wss://relay-a.example", "wss://relay-b.example")
    }
}
