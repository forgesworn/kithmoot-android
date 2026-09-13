package dev.forgesworn.kithmoot.g5

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.cadence.CadenceLeasePlan
import dev.forgesworn.kithmoot.cadence.CadenceOwnership
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.BothyPairing
import dev.forgesworn.kithmoot.protocol.BoxCadence
import dev.forgesworn.kithmoot.protocol.CadenceScope
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
            "probe" -> pairProbeWithdrawAndExclude()
            "restart-check" -> reconnectAndRetainExclusion()
            else -> throw AssertionError("unknown G7 cadence action")
        }
    }

    private fun pairProbeWithdrawAndExclude() {
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
        assertFalse(status.ready)
        assertEquals(listOf("store-key-provider", "tor-i2p-carriers"), status.missing)

        val plan = plan(nodeId, status.currentEpoch + 2)
        val stored = app.cadenceLeases.prepare(plan, epochSeconds())
        assertEquals(CadenceOwnership.CLIENT_EXCLUDED, stored.ownership)
        assertEquals((0 until 8).toList(), app.cadenceLeases.reservedCounters(ROOM, DEVICE, plan.startEpoch))
        put("g7-cadence-probe", buildJsonObject {
            put("authenticated", true); put("ready", status.ready); put("missing", status.missing.joinToString(","))
            put("linkPath", result.path.status); put("consentWithdrawalRefusedLateResult", true); put("countersExcluded", true)
            put("changedNodeCardRefused", true); put("unknownRouteRefused", true); put("changedMethodRefused", true)
            put("changedPathRefused", true); put("changedPayloadRefused", true); put("changedDeviceSignatureRefused", true)
            put("hostPinnedByBridge", true)
        })
    }

    private fun reconnectAndRetainExclusion() {
        val stored = app.cadenceLeases.all(ROOM, DEVICE).single()
        assertEquals(CadenceOwnership.CLIENT_EXCLUDED, stored.ownership)
        assertEquals((0 until 8).toList(), app.cadenceLeases.reservedCounters(ROOM, DEVICE, stored.plan.startEpoch))
        assertEquals(stored, app.cadenceLeases.prepare(stored.plan, epochSeconds()))
        val consent = app.linkConsents.all().single { it.roomId == ROOM }
        assertTrue(app.linkEngine.routeIds().contains(consent.routeId))
        val identity = identity(epochSeconds())
        val nodeId = consent.canonicalUrl.removePrefix("ws://").removeSuffix("/events")
        val status = app.cadenceClient.status(identity.participant, scope(identity, nodeId), "66".repeat(16), identity, epochSeconds()).get(120, TimeUnit.SECONDS)
        assertFalse(status.answer.ready)
        put("g7-cadence-restart", buildJsonObject {
            put("routeRestored", true); put("exactLeaseRetryRetained", true); put("countersStillExcluded", true)
            put("authenticatedStatusAfterRestart", true); put("linkPath", status.path.status)
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

    private fun scope(identity: PrimaryIdentity, nodeId: String) =
        CadenceScope(nodeId, ROOM, ROOM, 1, identity.participant, identity.devicePubkey, identity.credential, GRANT_ID)

    private fun consent(identity: PrimaryIdentity, nodeId: String, routeId: String, bothy: String) = LinkConsent(
        identity.participant, ROOM, bothy, routeId, "ws://$nodeId/events",
        listOf(get("ready").getValue("introduction_relay_url").jsonPrimitive.content), LinkConsentState.ACTIVE,
    )

    private fun plan(nodeId: String, start: Long): CadenceLeasePlan {
        val end = start + 2
        val body = buildJsonObject {
            put("v", 1); put("request_id", REQUEST_ID); put("lease_id", LEASE_ID); put("generation", 1)
            put("server", "ws://$nodeId/events"); put("room", ROOM); put("device", DEVICE)
            put("start_epoch", start); put("end_epoch", end); put("counter_lo", 0); put("counter_hi", 8)
        }.toString()
        return CadenceLeasePlan(nodeId, ROOM, ROOM, 1, DEVICE, LEASE_ID, 1, REQUEST_ID, body, start, end, 0, 8)
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
    }
}
