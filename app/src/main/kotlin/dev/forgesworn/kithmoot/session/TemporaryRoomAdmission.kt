package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlin.coroutines.coroutineContext

enum class AdmissionRequestPhase { SIGNING, SENDING, WAITING, RECONNECTING }

class RetiredInvitationException : Exception()
class DeclinedInvitationException : Exception("Someone in the room declined your request to join.")

/** One deadline covers the account signature and the invitation response.
 * Retirement is collected before asking an external signer; cancelling the
 * operation closes the subscription and prevents a late signature publishing.
 * The returned delegate owns a separate key, so the temporary key is wiped. */
suspend fun requestTemporaryRoomAdmission(
    transport: RoomTransport,
    invitation: RoomInvitation,
    name: String? = null,
    participant: String? = null,
    signer: ParticipantSigner? = null,
    timeoutMs: Long = 90_000,
    retryMs: Long = 2_000,
    stillCurrent: () -> Boolean = { true },
    onPhase: (AdmissionRequestPhase) -> Unit = {},
    now: () -> Long = { System.currentTimeMillis() / 1_000 },
    newRequestKey: () -> ByteArray = { Entropy.bytes(32) },
): RoomAdmission? {
    require(!invitation.persistent) { "A temporary admission requires a temporary invitation" }
    require(timeoutMs > 0 && retryMs > 0)
    val requesterKey = newRequestKey()
    val operation = currentCoroutineContext().job
    val generation = transport.publicationGeneration()
    try {
        return withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val invitationId = deriveInvitationId(invitation)
                val device = Schnorr.publicKeyHex(requesterKey)
                var requestId: String? = null
                val response = async(start = CoroutineStart.UNDISPATCHED) {
                    transport.subscribe(listOf(
                        Filter(kinds = listOf(KIND_INVITATION_GRANT), tags = mapOf(
                            "#d" to listOf(invitationId), "#p" to listOf(device))),
                        Filter(authors = listOf(invitation.canonicalInviter),
                            kinds = listOf(KIND_INVITATION_RETIREMENT),
                            tags = mapOf("#d" to listOf(invitationId))),
                    )).mapNotNull { event ->
                        if (decodeInvitationRetirement(event, invitation)) throw RetiredInvitationException()
                        check(stillCurrent()) { "The account changed. Try opening the room again." }
                        requestId?.let {
                            if (decodeInvitationDecline(event, invitation, requesterKey, it, now()) != null) throw DeclinedInvitationException()
                            decodeRoomAdmissionGrant(event, invitation, requesterKey, it, now())
                        }
                    }.first()
                }
                val createdAt = now()
                val account = participant?.lowercase()
                val proof = signer?.takeIf { it.pubkey.lowercase() == account }?.let { actor ->
                    onPhase(AdmissionRequestPhase.SIGNING)
                    val template = invitationAccountProofTemplate(invitation, device, createdAt)
                    actor.sign(template.kind, template.createdAt, template.tags, template.content)
                }
                coroutineContext.ensureActive()
                check(stillCurrent()) { "The account changed. Try opening the room again." }
                val request = encodeInvitationRequest(invitation, requesterKey, createdAt,
                    name = name, participant = account, accountProof = proof)
                requestId = request.id
                onPhase(AdmissionRequestPhase.SENDING)
                val retry = launch {
                    val dispatch = currentCoroutineContext().job
                    var acknowledged = false
                    while (isActive) {
                        check(stillCurrent()) { "The account changed. Try opening the room again." }
                        try {
                            val accepted = transport.publishConfirmedGuarded(request, generation, {
                                operation.isActive && dispatch.isActive && stillCurrent() && now() in createdAt until createdAt + 90
                            }, timeoutMs = minOf(timeoutMs, 10_000))
                            coroutineContext.ensureActive()
                            check(stillCurrent()) { "The account changed. Try opening the room again." }
                            if (accepted && !acknowledged) {
                                acknowledged = true
                                onPhase(AdmissionRequestPhase.WAITING)
                            } else if (!accepted && !acknowledged) onPhase(AdmissionRequestPhase.RECONNECTING)
                        } catch (failure: Exception) {
                            coroutineContext.ensureActive()
                            check(stillCurrent()) { "The account changed. Try opening the room again." }
                            if (!acknowledged) onPhase(AdmissionRequestPhase.RECONNECTING)
                        }
                        delay(retryMs)
                    }
                }
                try {
                    response.await().also {
                        check(stillCurrent()) { "The account changed. Try opening the room again." }
                    }
                } finally { retry.cancel() }
            }
        }
    } finally {
        requesterKey.fill(0)
    }
}
