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
/** Custody acknowledgement only. The original draft still needs relay proof. */
data class CadenceRecordingQueueResult(val custody: CadenceLeaseResult, val handoff: RecordingNodeHandoff)

/** Cancellation reaches the queued Link request; an in-flight offer cannot be retracted. */
private fun <T, R> CompletableFuture<T>.thenApplyCancellable(transform: (T) -> R): CompletableFuture<R> =
    thenApply { transform(it) }.also { mapped ->
        mapped.whenComplete { _, _ -> if (mapped.isCancelled) cancel(true) }
    }

/** All cadence control stays inside one consented, pinned ForgeSworn Link route. */
class CadenceClient(
    private val link: LinkJsonTransport,
    private val consents: LinkConsentVault,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    fun status(
        accountPubkey: String,
        scope: CadenceScope,
        requestId: String,
        identity: RoomIdentity,
        now: Long,
    ): CompletableFuture<CadenceStatusResult> {
        val body = BoxCadence.statusBody(scope, requestId, now)
        val signed = BoxCadence.sign(scope.nodeId, "POST", "/cadence/v1/status", body, identity.deviceSecretKey, now)
        return request(accountPubkey, scope, identity, signed).thenApplyCancellable { response ->
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
        stillAllowed: () -> Boolean = { true },
    ): CompletableFuture<CadenceLeaseResult> = queueRequest(
        accountPubkey, scope, identity, current, requestId, event, now, vault, null, stillAllowed)

    /** Queue acceptance is custody evidence only, never confirmed recording delivery. */
    fun queueGuarded(
        accountPubkey: String,
        scope: CadenceScope,
        identity: RoomIdentity,
        current: StoredCadenceLease,
        requestId: String,
        event: NostrEvent,
        publishBefore: Long,
        now: Long,
        vault: CadenceLeaseVault,
        stillAllowed: () -> Boolean,
    ): CompletableFuture<CadenceLeaseResult> = queueRequest(
        accountPubkey, scope, identity, current, requestId, event, now, vault, publishBefore, stillAllowed)

    private fun queueRequest(
        accountPubkey: String,
        scope: CadenceScope,
        identity: RoomIdentity,
        current: StoredCadenceLease,
        requestId: String,
        event: NostrEvent,
        now: Long,
        vault: CadenceLeaseVault,
        publishBefore: Long?,
        stillAllowed: () -> Boolean,
    ): CompletableFuture<CadenceLeaseResult> {
        val ownership = vault.publicationGeneration(scope.room, scope.device)
        validateLeaseScope(scope, identity, current)
        require(current.ownership == CadenceOwnership.BOX_OWNED) { "cadence lease ownership is unresolved" }
        require(vault.all(scope.room, scope.device).singleOrNull {
            it.plan.leaseId == current.plan.leaseId && it.plan.generation == current.plan.generation
        } == current) { "cadence lease changed before queueing" }
        require(publishBefore == null || publishBefore > now) { "cadence publication deadline expired" }
        fun allowed(): Boolean = stillAllowed() && (publishBefore == null || nowSeconds() < publishBefore)
        check(allowed()) { "cadence queue request was cancelled or expired" }
        val lease = exactLeaseBody(current)
        val body = if (publishBefore == null) BoxCadence.queueBody(lease, requestId, event)
            else BoxCadence.guardedQueueBody(lease, requestId, event, publishBefore)
        val path = if (publishBefore == null) BoxCadence.queuePath(current.plan.leaseId)
            else BoxCadence.guardedQueuePath(current.plan.leaseId)
        val signed = BoxCadence.sign(scope.nodeId, "POST", path, body, identity.deviceSecretKey, now)
        return receiptRequest(accountPubkey, scope, identity, signed, current, now, vault) {
            allowed() && vault.publicationGeneration(scope.room, scope.device) == ownership
        }
    }

    /** The journal owns the original request before any network handoff. */
    fun queueRecording(
        accountPubkey: String,
        scope: CadenceScope,
        identity: RoomIdentity,
        current: StoredCadenceLease,
        binding: RecordingNodeHandoffBinding,
        now: Long,
        vault: CadenceLeaseVault,
        journal: RecordingNodeHandoffJournal,
        stillAllowed: () -> Boolean,
    ): CompletableFuture<CadenceRecordingQueueResult> {
        validateRecordingScope(accountPubkey, scope, identity, current, binding)
        val credentialExpiry = originalRecordingCredentialExpiry(binding)
        fun allowed() = stillAllowed() && nowSeconds() < credentialExpiry
        check(allowed()) { "The original recording publication was revoked or expired" }
        journal.reserveOffer(binding)
        return try {
            queueGuarded(accountPubkey, scope, identity, current, binding.requestId, binding.event,
                binding.publishBefore, now, vault) { allowed() && journal.stillAllowed(binding) }
                .thenApplyCancellable { result -> CadenceRecordingQueueResult(result, journal.queued(binding)) }
                .also { future -> future.whenComplete { _, _ -> journal.endOffer(binding) } }
        } catch (failure: Exception) {
            journal.endOffer(binding)
            throw failure
        }
    }

    /** The retained withdrawal is signed anew only at the HTTP authentication
     * layer. Its body, request ID, event and original node never change. */
    fun withdrawRecording(
        accountPubkey: String,
        scope: CadenceScope,
        identity: RoomIdentity,
        current: StoredCadenceLease,
        binding: RecordingNodeHandoffBinding,
        withdrawalRequestId: String,
        now: Long,
        vault: CadenceLeaseVault,
        journal: RecordingNodeHandoffJournal,
        stillAllowed: () -> Boolean,
    ): CompletableFuture<CadenceLeaseResult> {
        validateRecordingScope(accountPubkey, scope, identity, current, binding)
        val credentialExpiry = originalRecordingCredentialExpiry(binding)
        fun allowed() = stillAllowed() && nowSeconds() < credentialExpiry
        check(allowed()) { "The original recording withdrawal was revoked or expired" }
        val ownership = vault.publicationGeneration(scope.room, scope.device)
        val withdrawal = journal.reserveWithdrawal(binding, withdrawalRequestId)
        return try {
            val body = Json.parseToJsonElement(withdrawal.body).jsonObject
            val signed = BoxCadence.sign(binding.nodeId, "POST", withdrawal.path, body, identity.deviceSecretKey, now)
            receiptRequest(accountPubkey, scope, identity, signed, current, now, vault) {
                allowed() && journal.withdrawalStillAllowed(binding, withdrawal) &&
                    vault.publicationGeneration(scope.room, scope.device) == ownership
            }.thenApplyCancellable { result ->
                check(result.lease.receipt?.code == "withdrawn") { "The original node did not confirm withdrawal" }
                journal.withdrawn(binding)
                result
            }.also { future -> future.whenComplete { _, _ -> journal.endWithdrawal(binding) } }
        } catch (failure: Exception) {
            journal.endWithdrawal(binding)
            throw failure
        }
    }

    private fun validateRecordingScope(accountPubkey: String, scope: CadenceScope, identity: RoomIdentity,
        current: StoredCadenceLease, binding: RecordingNodeHandoffBinding) {
        validateLeaseScope(scope, identity, current)
        require(binding.accountPubkey == accountPubkey && binding.nodeId == scope.nodeId &&
            binding.leaseBody == current.plan.requestBody) { "Recording handoff does not belong to the original account, node and lease" }
        require(binding.lease.getValue("grant_id").jsonPrimitive.content == scope.grantId) {
            "Recording handoff does not belong to the original grant"
        }
    }

    private fun originalRecordingCredentialExpiry(binding: RecordingNodeHandoffBinding): Long =
        NostrEvent.fromJson(binding.lease.getValue("credential")).tagValue("expiration")?.toLongOrNull() ?: 0L

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
        stillAllowed: () -> Boolean = { true },
    ): CompletableFuture<CadenceLeaseResult> = request(accountPubkey, scope, identity, signed, stillAllowed).thenApplyCancellable { response ->
        CadenceLeaseResult(vault.accept(current, BoxCadence.parseReceipt(response.status, response.body), now), response.path)
    }

    private fun request(
        accountPubkey: String,
        scope: CadenceScope,
        identity: RoomIdentity,
        signed: CadenceSignedRequest,
        stillAllowed: () -> Boolean = { true },
    ) = run {
        validateIdentity(scope, identity)
        val canonicalUrl = BoxCadence.server(scope.nodeId)
        val routeId = requireNotNull(consents.activeRoute(accountPubkey, scope.room, canonicalUrl)) {
            "cadence requires active room consent for this Link route"
        }
        link.request(LinkJsonRequest(routeId, signed.method, signed.path, signed.authorization, signed.body,
            stillAllowed = { consents.activeRoute(accountPubkey, scope.room, canonicalUrl) == routeId && stillAllowed() }))
            .thenApplyCancellable { response ->
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
