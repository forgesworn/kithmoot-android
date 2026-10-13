package dev.forgesworn.kithmoot.cadence

import dev.forgesworn.kithmoot.protocol.CadenceScope
import dev.forgesworn.kithmoot.session.RoomIdentity
import java.util.concurrent.CompletableFuture

/** Cleanup of accepted custody uses the captured original identity and route,
 * independently of the screen or the permission to publish another message.
 * CadenceClient fences the original credential expiry, grant, consent and
 * lease ownership at dispatch; a reopened room's credential cannot extend them.
 */
class OriginalRecordingWithdrawal(
    private val accountPubkey: String,
    private val scope: CadenceScope,
    private val identity: RoomIdentity,
    private val client: CadenceClient,
    private val leases: CadenceLeaseVault,
    private val journal: RecordingNodeHandoffJournal,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    fun withdraw(binding: RecordingNodeHandoffBinding, requestId: String): CompletableFuture<CadenceLeaseResult> {
        val original = leases.all(scope.room, scope.device).single { it.plan.requestBody == binding.leaseBody }
        val retained = journal.pending(accountPubkey).single { it.binding == binding }
        return client.withdrawRecording(accountPubkey, scope, identity, original, binding,
            retained.withdrawal?.requestId ?: requestId, now(), leases, journal) { true }
    }
}
