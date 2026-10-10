package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect

enum class AdmissionDecisionPhase { WAITING, SENDING, RETRY }
enum class AdmissionDecision { ADMIT, DECLINE }

data class PendingInvitationAdmission(
    val requestId: String,
    val device: String,
    val name: String?,
    val claimedParticipant: String?,
    val verifiedParticipant: String?,
    val expiresAt: Long,
    val phase: AdmissionDecisionPhase = AdmissionDecisionPhase.WAITING,
    val error: String? = null,
    val decision: AdmissionDecision = AdmissionDecision.ADMIT,
)

/** A temporary link never grants a room key merely because its bearer asked.
 * A queued request has a stable event ID, a bounded lifetime and an individual
 * decision. Confirmation means a relay accepted the grant, not that the guest
 * joined. Decline sends an authenticated refusal; dismissal remains local. */
class TemporaryRoomAdmissionDesk(
    private val scope: CoroutineScope,
    private val transport: RoomTransport,
    host: RoomInvitationHost,
    roomSecret: ByteArray,
    private val epoch: () -> Int?,
    private val stillCurrent: () -> Boolean,
    private val onRetired: suspend () -> Unit = {},
    private val onGrantAccepted: (String) -> Unit = {},
    private val onDeclineAccepted: (String) -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() / 1_000 },
    private val confirmationTimeoutMs: Long = 15_000,
) {
    private data class Entry(val row: PendingInvitationAdmission, val grant: NostrEvent? = null)
    private val lock = Any()
    private val host = RoomInvitationHost(host.invitation, host.inviterSecretKey.copyOf(), host.delegation.toList())
    private val secret = roomSecret.copyOf()
    private val sourceEpoch = epoch()
    private val sourceGeneration = transport.publicationGeneration()
    private val responder = Schnorr.publicKeyHex(host.inviterSecretKey)
    private val invitationId = deriveInvitationId(host.invitation)
    private val entries = linkedMapOf<String, Entry>()
    private val seen = linkedSetOf<String>()
    private val mutablePending = MutableStateFlow<List<PendingInvitationAdmission>>(emptyList())
    val pending: StateFlow<List<PendingInvitationAdmission>> = mutablePending.asStateFlow()
    private var retired = false
    private var closed = false
    private var job: Job? = null

    private fun allowed(): Boolean = !closed && !retired && job?.isActive != false && stillCurrent() &&
        epoch() == sourceEpoch && host.delegation.all { it.expiresAt > now() }

    private fun publishRows() { mutablePending.value = entries.values.map { it.row } }

    fun start(): Job = synchronized(lock) {
        check(job == null && !closed)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                coroutineScope {
                    launch {
                        while (isActive) {
                            delay(1_000)
                            synchronized(lock) {
                                if (!allowed()) entries.clear()
                                else entries.entries.removeAll { now() >= it.value.row.expiresAt }
                                publishRows()
                            }
                        }
                    }
                    transport.subscribe(listOf(
                        Filter(kinds = listOf(KIND_INVITATION_REQUEST), tags = mapOf(
                            "#d" to listOf(invitationId), "#p" to listOf(host.invitation.canonicalInviter))),
                        Filter(authors = listOf(host.invitation.canonicalInviter),
                            kinds = listOf(KIND_INVITATION_RETIREMENT), tags = mapOf("#d" to listOf(invitationId))),
                    )).collect { event ->
                        if (decodeInvitationRetirement(event, host.invitation)) {
                            synchronized(lock) { retired = true; entries.clear(); publishRows() }
                            onRetired()
                        } else receive(event)
                    }
                    awaitCancellation()
                }
            } finally {
                synchronized(lock) {
                    closed = true; entries.clear(); publishRows(); secret.fill(0); host.inviterSecretKey.fill(0)
                }
            }
        }.also { job = it }
    }

    private fun receive(event: NostrEvent) = synchronized(lock) {
        if (host.invitation.persistent || !allowed()) return
        val request = decodeInvitationRequest(event, host.invitation, now()) ?: return
        if (request.device == responder || request.requestId in seen || request.requestId in entries || entries.size >= 64) return
        val expiresAt = minOf(event.createdAt + INVITATION_MAX_AGE_SECONDS, now() + INVITATION_MAX_AGE_SECONDS)
        if (now() >= expiresAt) return
        seen.add(request.requestId)
        while (seen.size > 256) seen.remove(seen.first())
        entries[request.requestId] = Entry(PendingInvitationAdmission(request.requestId,
            request.device, DisplayName.sanitise(request.name), request.participant,
            request.verifiedParticipant, expiresAt))
        publishRows()
    }

    fun dismiss(requestId: String) = synchronized(lock) {
        // Do not imply an already offered grant can be recalled by dismissing it.
        if (entries[requestId]?.row?.phase == AdmissionDecisionPhase.SENDING) return
        entries.remove(requestId)
        publishRows()
    }

    fun admit(requestId: String) = answer(requestId, AdmissionDecision.ADMIT)
    fun decline(requestId: String) = answer(requestId, AdmissionDecision.DECLINE)

    private fun answer(requestId: String, decision: AdmissionDecision) {
        val selected = synchronized(lock) {
            val entry = entries[requestId] ?: return
            if (!allowed() || now() >= entry.row.expiresAt) {
                entries.remove(requestId); publishRows(); return
            }
            if (entry.row.phase == AdmissionDecisionPhase.SENDING ||
                entry.grant != null && entry.row.decision != decision) return
            entries[requestId] = entry.copy(row = entry.row.copy(phase = AdmissionDecisionPhase.SENDING, error = null, decision = decision))
            publishRows()
            entry
        }
        val owner = job ?: return
        CoroutineScope(scope.coroutineContext + owner).launch {
            try {
                val grant = selected.grant ?: synchronized(lock) {
                    check(allowed() && now() < selected.row.expiresAt)
                    if (decision == AdmissionDecision.DECLINE) encodeInvitationDecline(host, selected.row.device, requestId, now())
                    else encodeInvitationGrant(host, selected.row.device, requestId, secret, now(), epoch = sourceEpoch)
                }
                synchronized(lock) {
                    val entry = entries[requestId] ?: return@launch
                    entries[requestId] = entry.copy(grant = grant)
                }
                coroutineContext.ensureActive()
                val accepted = transport.publishConfirmedGuarded(grant, sourceGeneration, {
                    synchronized(lock) { allowed() && entries.containsKey(requestId) && now() < selected.row.expiresAt }
                }, confirmationTimeoutMs)
                synchronized(lock) {
                    val entry = entries[requestId] ?: return@launch
                    if (!allowed() || now() >= entry.row.expiresAt) entries.remove(requestId)
                    else if (accepted) {
                        entries.remove(requestId)
                        if (decision == AdmissionDecision.DECLINE) onDeclineAccepted(requestId)
                        else onGrantAccepted(requestId)
                    } else entries[requestId] = entry.copy(row = entry.row.copy(
                        phase = AdmissionDecisionPhase.RETRY,
                        error = if (decision == AdmissionDecision.DECLINE) "No relay confirmed the refusal. The guest may still have received it; retry sends the same refusal."
                            else "No relay confirmed the grant. The guest may still have received it; retry sends the same grant."))
                    publishRows()
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                synchronized(lock) {
                    entries[requestId]?.let { entry ->
                        if (!allowed() || now() >= entry.row.expiresAt) entries.remove(requestId)
                        else entries[requestId] = entry.copy(row = entry.row.copy(
                            phase = AdmissionDecisionPhase.RETRY, error = if (decision == AdmissionDecision.DECLINE)
                                "The refusal was not confirmed. Retry while the guest is waiting."
                                else "The grant was not confirmed. Retry while the guest is waiting."))
                    }
                    publishRows()
                }
            }
        }
    }
}
