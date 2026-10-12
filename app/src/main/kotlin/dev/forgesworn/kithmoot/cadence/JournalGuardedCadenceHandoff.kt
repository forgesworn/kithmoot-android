package dev.forgesworn.kithmoot.cadence

import dev.forgesworn.kithmoot.protocol.CadenceScope
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.PublicationNotOfferedException
import dev.forgesworn.kithmoot.session.RoomIdentity
import java.util.concurrent.CompletableFuture

/** One explicit original-account/node context. Recovery does not enqueue;
 * only the recording owner's bounded transport request calls this adapter. */
class JournalGuardedCadenceHandoff(
    private val accountPubkey: String,
    private val originalScope: CadenceScope,
    private val identity: RoomIdentity,
    private val client: CadenceClient,
    private val leases: CadenceLeaseVault,
    private val journal: RecordingNodeHandoffJournal,
    private val bindingFor: (StoredCadenceLease, NostrEvent, Long) -> RecordingNodeHandoffBinding,
    private val receivedEventConfirmsPublication: (String) -> Boolean,
    private val onRevoked: (RecordingNodeHandoffBinding) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : GuardedCadenceHandoff {
    override fun queue(lease: StoredCadenceLease, event: NostrEvent, publishBefore: Long,
        stillAllowed: () -> Boolean): CompletableFuture<Unit> {
        if (!stillAllowed()) throw PublicationNotOfferedException()
        val binding = bindingFor(lease, event, publishBefore)
        require(binding.accountPubkey == accountPubkey && binding.nodeId == originalScope.nodeId &&
            binding.leaseBody == lease.plan.requestBody && binding.event == event && binding.publishBefore <= publishBefore) {
            "The original recording node handoff changed"
        }
        val retained = journal.prepare(binding)
        if (!stillAllowed()) throw PublicationNotOfferedException()
        // Retry resolves relay proof for accepted custody; it does not enqueue
        // the same recording again merely because its relay observation is late.
        if (retained.state == RecordingNodeHandoffState.QUEUED) return CompletableFuture.completedFuture(Unit)
        val current = leases.all(originalScope.room, originalScope.device).single {
            it.plan.leaseId == lease.plan.leaseId && it.plan.generation == lease.plan.generation
        }
        val request = client.queueRecording(accountPubkey, originalScope, identity, current,
            binding, now(), leases, journal, stillAllowed)
        return request.thenApply { Unit }.also { mapped ->
            mapped.whenComplete { _, _ -> if (mapped.isCancelled) request.cancel(true) }
        }
    }

    override fun confirmed(event: NostrEvent) {
        val original = journal.pending(accountPubkey).singleOrNull { it.binding.event == event }
        if (original == null) {
            check(journal.isConfirmed(accountPubkey, event)) { "The original recording node handoff is missing" }
            return
        }
        journal.confirmRelay(original.binding, receivedEventConfirmsPublication)
    }

    override fun revoked(event: NostrEvent) {
        val original = journal.pending(accountPubkey).singleOrNull { it.binding.event == event } ?: return
        journal.revoke(original.binding)
        if (original.state in setOf(RecordingNodeHandoffState.OFFERED, RecordingNodeHandoffState.QUEUED,
                RecordingNodeHandoffState.CANCEL_PENDING)) onRevoked(original.binding)
    }
}
