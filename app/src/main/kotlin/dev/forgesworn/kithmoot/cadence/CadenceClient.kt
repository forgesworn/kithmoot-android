package dev.forgesworn.kithmoot.cadence

import dev.forgesworn.kithmoot.protocol.BoxCadence
import dev.forgesworn.kithmoot.protocol.CadenceLeaseOptions
import dev.forgesworn.kithmoot.protocol.CadenceScope
import dev.forgesworn.kithmoot.protocol.CadenceSignedRequest
import dev.forgesworn.kithmoot.protocol.CadenceStatusAnswer
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.LinkConsentVault
import dev.forgesworn.kithmoot.relay.LinkJsonRequest
import dev.forgesworn.kithmoot.relay.LinkJsonTransport
import dev.forgesworn.kithmoot.relay.LinkPathState
import dev.forgesworn.kithmoot.session.RoomIdentity
import java.util.concurrent.CompletableFuture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

data class CadenceStatusResult(val answer: CadenceStatusAnswer, val path: LinkPathState)
data class CadenceLeaseResult(val lease: StoredCadenceLease, val path: LinkPathState)

/** All cadence control stays inside one consented, pinned ForgeSworn Link route. */
class CadenceClient(private val link: LinkJsonTransport, private val consents: LinkConsentVault) {
    fun status(
        accountPubkey: String,
        scope: CadenceScope,
        requestId: String,
        identity: RoomIdentity,
        now: Long,
    ): CompletableFuture<CadenceStatusResult> {
        val body = BoxCadence.statusBody(scope, requestId, now)
        val signed = BoxCadence.sign(scope.nodeId, "POST", "/cadence/v1/status", body, identity.deviceSecretKey, now)
        return request(accountPubkey, scope, identity, signed).thenApply { response ->
            CadenceStatusResult(BoxCadence.parseStatus(response.status, response.body), response.path)
        }
    }

    /** Excludes the counters durably before the first lease byte can cross Link. */
    fun stage(
        accountPubkey: String,
        options: CadenceLeaseOptions,
        identity: RoomIdentity,
        now: Long,
        vault: CadenceLeaseVault,
    ): CompletableFuture<CadenceLeaseResult> {
        val body = BoxCadence.leaseBody(options)
        val excluded = vault.prepare(plan(options, body), now)
        return sendLease(accountPubkey, options.scope, identity, excluded, now, vault)
    }

    /** Resolve an unknown outcome after a timeout using the exact retained body. */
    fun retryStage(
        accountPubkey: String,
        scope: CadenceScope,
        identity: RoomIdentity,
        current: StoredCadenceLease,
        now: Long,
        vault: CadenceLeaseVault,
    ): CompletableFuture<CadenceLeaseResult> = sendLease(accountPubkey, scope, identity, current, now, vault)

    fun leaseStatus(
        accountPubkey: String,
        scope: CadenceScope,
        identity: RoomIdentity,
        current: StoredCadenceLease,
        requestId: String,
        now: Long,
        vault: CadenceLeaseVault,
    ): CompletableFuture<CadenceLeaseResult> {
        validateLeaseScope(scope, identity, current)
        val body = BoxCadence.statusBody(scope, requestId, now, current.plan.leaseId, current.plan.generation)
        val signed = BoxCadence.sign(scope.nodeId, "POST", BoxCadence.leaseStatusPath(current.plan.leaseId), body, identity.deviceSecretKey, now)
        return receiptRequest(accountPubkey, scope, identity, signed, current, now, vault)
    }

    fun queue(
        accountPubkey: String,
        scope: CadenceScope,
        identity: RoomIdentity,
        current: StoredCadenceLease,
        requestId: String,
        event: NostrEvent,
        now: Long,
        vault: CadenceLeaseVault,
    ): CompletableFuture<CadenceLeaseResult> {
        validateLeaseScope(scope, identity, current)
        require(current.ownership == CadenceOwnership.BOX_OWNED) { "cadence lease ownership is unresolved" }
        val body = BoxCadence.queueBody(exactLeaseBody(current), requestId, event)
        val signed = BoxCadence.sign(scope.nodeId, "POST", BoxCadence.queuePath(current.plan.leaseId), body, identity.deviceSecretKey, now)
        return receiptRequest(accountPubkey, scope, identity, signed, current, now, vault)
    }

    fun stop(
        accountPubkey: String,
        scope: CadenceScope,
        identity: RoomIdentity,
        current: StoredCadenceLease,
        requestId: String,
        boundaryEpoch: Long,
        now: Long,
        vault: CadenceLeaseVault,
    ): CompletableFuture<CadenceLeaseResult> {
        validateLeaseScope(scope, identity, current)
        require(current.ownership == CadenceOwnership.BOX_OWNED) { "cadence lease ownership is unresolved" }
        val body = BoxCadence.mutationBody(exactLeaseBody(current), requestId, boundaryEpoch)
        val signed = BoxCadence.sign(scope.nodeId, "PUT", BoxCadence.stopPath(current.plan.leaseId), body, identity.deviceSecretKey, now)
        return receiptRequest(accountPubkey, scope, identity, signed, current, now, vault)
    }

    fun rekey(
        accountPubkey: String,
        scope: CadenceScope,
        identity: RoomIdentity,
        current: StoredCadenceLease,
        requestId: String,
        nextRoomGeneration: Long,
        now: Long,
        vault: CadenceLeaseVault,
    ): CompletableFuture<CadenceLeaseResult> {
        validateLeaseScope(scope, identity, current)
        require(current.ownership == CadenceOwnership.BOX_OWNED) { "cadence lease ownership is unresolved" }
        val body = BoxCadence.rekeyBody(exactLeaseBody(current), requestId, nextRoomGeneration)
        val signed = BoxCadence.sign(scope.nodeId, "PUT", BoxCadence.rekeyPath(current.plan.leaseId), body, identity.deviceSecretKey, now)
        return receiptRequest(accountPubkey, scope, identity, signed, current, now, vault)
    }

    fun withdraw(
        accountPubkey: String,
        scope: CadenceScope,
        identity: RoomIdentity,
        current: StoredCadenceLease,
        requestId: String,
        eventId: String,
        now: Long,
        vault: CadenceLeaseVault,
    ): CompletableFuture<CadenceLeaseResult> {
        validateLeaseScope(scope, identity, current)
        require(current.ownership == CadenceOwnership.BOX_OWNED) { "cadence lease ownership is unresolved" }
        val body = BoxCadence.mutationBody(exactLeaseBody(current), requestId, null)
        val signed = BoxCadence.sign(scope.nodeId, "POST", BoxCadence.withdrawPath(current.plan.leaseId, eventId), body, identity.deviceSecretKey, now)
        return receiptRequest(accountPubkey, scope, identity, signed, current, now, vault)
    }

    private fun sendLease(
        accountPubkey: String,
        scope: CadenceScope,
        identity: RoomIdentity,
        current: StoredCadenceLease,
        now: Long,
        vault: CadenceLeaseVault,
    ): CompletableFuture<CadenceLeaseResult> {
        validateLeaseScope(scope, identity, current)
        val body = exactLeaseBody(current)
        val signed = BoxCadence.sign(scope.nodeId, "PUT", BoxCadence.leasePath(current.plan.leaseId), body, identity.deviceSecretKey, now)
        return receiptRequest(accountPubkey, scope, identity, signed, current, now, vault)
    }

    private fun receiptRequest(
        accountPubkey: String,
        scope: CadenceScope,
        identity: RoomIdentity,
        signed: CadenceSignedRequest,
        current: StoredCadenceLease,
        now: Long,
        vault: CadenceLeaseVault,
    ): CompletableFuture<CadenceLeaseResult> = request(accountPubkey, scope, identity, signed).thenApply { response ->
        CadenceLeaseResult(vault.accept(current, BoxCadence.parseReceipt(response.status, response.body), now), response.path)
    }

    private fun request(
        accountPubkey: String,
        scope: CadenceScope,
        identity: RoomIdentity,
        signed: CadenceSignedRequest,
    ) = run {
        validateIdentity(scope, identity)
        val canonicalUrl = BoxCadence.server(scope.nodeId)
        val routeId = requireNotNull(consents.activeRoute(accountPubkey, scope.room, canonicalUrl)) {
            "cadence requires active room consent for this Link route"
        }
        link.request(LinkJsonRequest(routeId, signed.method, signed.path, signed.authorization, signed.body))
            .thenApply { response ->
                check(consents.activeRoute(accountPubkey, scope.room, canonicalUrl) == routeId) {
                    "cadence consent changed while the request was in flight"
                }
                response
            }
    }

    private fun validateIdentity(scope: CadenceScope, identity: RoomIdentity) {
        require(identity.devicePubkey == scope.device && identity.participant == scope.persona && identity.credential == scope.credential) {
            "cadence scope does not belong to this room identity"
        }
    }

    private fun validateLeaseScope(scope: CadenceScope, identity: RoomIdentity, current: StoredCadenceLease) {
        validateIdentity(scope, identity)
        require(current.plan.nodeId == scope.nodeId && current.plan.room == scope.room &&
            current.plan.trafficRoom == scope.trafficRoom && current.plan.roomGeneration == scope.roomGeneration &&
            current.plan.device == scope.device) {
            "cadence lease does not belong to this scope"
        }
    }

    private fun exactLeaseBody(current: StoredCadenceLease): JsonObject =
        Json.parseToJsonElement(current.plan.requestBody).jsonObject.also {
            require(it.toString() == current.plan.requestBody) { "cadence lease bytes are no longer canonical" }
        }

    private fun plan(options: CadenceLeaseOptions, body: JsonObject) = CadenceLeasePlan(
        options.scope.nodeId,
        options.scope.room,
        options.scope.trafficRoom,
        options.scope.roomGeneration,
        options.scope.device,
        options.leaseId,
        options.generation,
        options.requestId,
        body.toString(),
        options.startEpoch,
        options.endEpoch,
        body.getValue("counter_lo").jsonPrimitive.long.toInt(),
        body.getValue("counter_hi").jsonPrimitive.long.toInt(),
    )
}
