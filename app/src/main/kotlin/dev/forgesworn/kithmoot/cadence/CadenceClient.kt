package dev.forgesworn.kithmoot.cadence

import dev.forgesworn.kithmoot.protocol.BoxCadence
import dev.forgesworn.kithmoot.protocol.CadenceScope
import dev.forgesworn.kithmoot.protocol.CadenceStatusAnswer
import dev.forgesworn.kithmoot.relay.LinkJsonRequest
import dev.forgesworn.kithmoot.relay.LinkJsonTransport
import dev.forgesworn.kithmoot.relay.LinkConsentVault
import dev.forgesworn.kithmoot.relay.LinkPathState
import dev.forgesworn.kithmoot.session.RoomIdentity
import java.util.concurrent.CompletableFuture

data class CadenceStatusResult(val answer: CadenceStatusAnswer, val path: LinkPathState)

/** Status-only activation seam. Lease ownership remains disabled until Bothy reports ready. */
class CadenceClient(private val link: LinkJsonTransport, private val consents: LinkConsentVault) {
    fun status(
        accountPubkey: String,
        scope: CadenceScope,
        requestId: String,
        identity: RoomIdentity,
        now: Long,
    ): CompletableFuture<CadenceStatusResult> {
        require(identity.devicePubkey == scope.device && identity.participant == scope.persona && identity.credential == scope.credential) {
            "cadence scope does not belong to this room identity"
        }
        val canonicalUrl = BoxCadence.server(scope.nodeId)
        val routeId = requireNotNull(consents.activeRoute(accountPubkey, scope.room, canonicalUrl)) {
            "cadence requires active room consent for this Link route"
        }
        val body = BoxCadence.statusBody(scope, requestId, now)
        val signed = BoxCadence.sign(scope.nodeId, "POST", "/cadence/v1/status", body, identity.deviceSecretKey, now)
        return link.request(LinkJsonRequest(routeId, signed.method, signed.path, signed.authorization, signed.body))
            .thenApply { response ->
                check(consents.activeRoute(accountPubkey, scope.room, canonicalUrl) == routeId) {
                    "cadence consent changed while the request was in flight"
                }
                CadenceStatusResult(BoxCadence.parseStatus(response.status, response.body), response.path)
            }
    }
}
