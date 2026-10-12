package dev.forgesworn.kithmoot.cadence

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import org.junit.Assert.*
import org.junit.Test

class RecordingNodeHandoffJournalTest {
    private val start = 1_800_000_000L
    private class Storage : RoomStorage {
        var bytes: ByteArray? = null
        var fail = false
        override fun read(): ByteArray? = bytes?.copyOf()
        override fun reset() { bytes = null }
        override fun write(bytes: ByteArray) { check(!fail) { "storage failed" }; this.bytes = bytes.copyOf() }
    }
    private fun binding(): RecordingNodeHandoffBinding {
        val persona = "ee".repeat(32).hexToBytes()
        val deviceSecret = "dd".repeat(32).hexToBytes()
        val room = "42".repeat(32)
        val device = Schnorr.publicKeyHex(deviceSecret)
        val credential = createDeviceCredential(persona, device, room, start + 14_400, start, ByteArray(32))
        val scope = CadenceScope("a".repeat(52), room, room, 1, Schnorr.publicKeyHex(persona), device, credential, "33".repeat(16))
        val lease = BoxCadence.leaseBody(CadenceLeaseOptions(scope, "11".repeat(16), "22".repeat(16), 1, 0,
            500000, 500002, 500004, ByteArray(32) { 9 },
            listOf("wss://relay-a.example", "wss://relay-b.example"), listOf("local"), start))
        val event = Events.sign(deviceSecret, 1460, start, listOf(listOf("d", room)), "recording ciphertext", ByteArray(32))
        return RecordingNodeHandoffBinding("00000000-0000-0000-0000-000000000001", scope.persona,
            scope.nodeId, lease.toString(), "44".repeat(16), event, start + 60)
    }

    @Test fun restartRetainsExactOriginalRequestAndOwnerInsteadOfSelectingAnotherNode() {
        val storage = Storage()
        val binding = binding()
        val first = RecordingNodeHandoffJournal(storage) { start }.apply { recover() }
        first.prepare(binding)
        first.reserveOffer(binding)
        val restarted = RecordingNodeHandoffJournal(storage) { start }.apply { recover() }
        val original = restarted.pending(binding.accountPubkey).single()
        assertEquals(binding, original.binding)
        assertEquals(binding.queueBody, original.binding.queueBody)
        assertEquals(binding.path, original.binding.path)
        assertEquals(RecordingNodeHandoffState.OFFERED, original.state)
        assertTrue(restarted.pending("ff".repeat(32)).isEmpty())
        assertFalse(restarted.stillAllowed(binding))
        restarted.reserveOffer(binding)
        assertTrue(restarted.stillAllowed(binding))
        assertThrows(IllegalStateException::class.java) { restarted.reserveOffer(binding) }
        restarted.endOffer(binding)
        assertFalse(restarted.stillAllowed(binding))
        assertEquals(RecordingNodeHandoffState.OFFERED, restarted.pending(binding.accountPubkey).single().state)
        restarted.reserveOffer(binding)
        assertTrue(restarted.stillAllowed(binding))

        assertThrows(IllegalStateException::class.java) { restarted.prepare(binding.copy(publishBefore = binding.publishBefore + 1)) }
        assertThrows(IllegalStateException::class.java) { restarted.prepare(binding.copy(requestId = "55".repeat(16))) }
        assertEquals(original, restarted.pending(binding.accountPubkey).single())
    }

    @Test fun queueReceiptCannotCompleteADraftAndOnlyTheExactRelayEventCanConfirm() {
        val storage = Storage()
        val binding = binding()
        val journal = RecordingNodeHandoffJournal(storage) { start }.apply { recover() }
        journal.prepare(binding)
        journal.reserveOffer(binding)
        assertTrue(journal.stillAllowed(binding))
        journal.queued(binding)
        assertFalse(journal.stillAllowed(binding))
        assertEquals(RecordingNodeHandoffState.QUEUED, journal.pending(binding.accountPubkey).single().state)
        assertThrows(IllegalStateException::class.java) { journal.confirmRelay(binding) { false } }
        assertThrows(IllegalStateException::class.java) { journal.retire(binding) }
        var checked: String? = null
        assertEquals(RecordingNodeHandoffState.CONFIRMED, journal.confirmRelay(binding) {
            checked = it; it == binding.event.id
        }.state)
        assertEquals(binding.event.id, checked)
        journal.retire(binding)
        assertTrue(journal.pending(binding.accountPubkey).isEmpty())
    }

    @Test fun cancellationSurvivesLostReplyLateQueueAckAndRestart() {
        val storage = Storage()
        val binding = binding()
        val journal = RecordingNodeHandoffJournal(storage) { start }.apply { recover() }
        journal.prepare(binding)
        journal.reserveOffer(binding)
        journal.revokeRoom(binding.room)
        assertFalse(journal.stillAllowed(binding))
        assertEquals(RecordingNodeHandoffState.CANCEL_PENDING, journal.queued(binding).state)
        val restarted = RecordingNodeHandoffJournal(storage) { start }.apply { recover() }
        assertFalse(restarted.stillAllowed(binding))
        assertEquals(RecordingNodeHandoffState.CANCEL_PENDING, restarted.pending(binding.accountPubkey).single().state)
        assertThrows(IllegalStateException::class.java) { restarted.reserveOffer(binding) }
        assertThrows(IllegalStateException::class.java) { restarted.confirmRelay(binding) { true } }
        restarted.reserveWithdrawal(binding, "66".repeat(16))
        restarted.withdrawn(binding)
        restarted.retire(binding)
        assertTrue(restarted.pending(binding.accountPubkey).isEmpty())
    }

    @Test fun lostWithdrawalReplyRetainsOriginalIdBodyPathAndRequiresExplicitRestartReservation() {
        val storage = Storage()
        val binding = binding()
        val journal = RecordingNodeHandoffJournal(storage) { start }.apply { recover() }
        journal.prepare(binding)
        journal.reserveOffer(binding)
        journal.revokeRoom(binding.room)
        val withdrawal = journal.reserveWithdrawal(binding, "66".repeat(16))
        assertEquals("${binding.path}/${binding.event.id}/withdraw", withdrawal.path)
        assertTrue(journal.withdrawalStillAllowed(binding, withdrawal))
        assertThrows(IllegalStateException::class.java) { journal.reserveWithdrawal(binding, withdrawal.requestId) }
        val restarted = RecordingNodeHandoffJournal(storage) { start }.apply { recover() }
        assertEquals(withdrawal, restarted.pending(binding.accountPubkey).single().withdrawal)
        assertFalse(restarted.withdrawalStillAllowed(binding, withdrawal))
        assertThrows(IllegalStateException::class.java) { restarted.reserveWithdrawal(binding, "77".repeat(16)) }
        assertEquals(withdrawal, restarted.reserveWithdrawal(binding, withdrawal.requestId))
        assertTrue(restarted.withdrawalStillAllowed(binding, withdrawal))
        restarted.endWithdrawal(binding)
        assertFalse(restarted.withdrawalStillAllowed(binding, withdrawal))
        assertEquals(withdrawal, restarted.reserveWithdrawal(binding, withdrawal.requestId))
        restarted.withdrawn(binding)
        assertFalse(restarted.withdrawalStillAllowed(binding, withdrawal))
    }

    @Test fun deadlineAndFailedRevocationWriteBothFenceDispatch() {
        val storage = Storage()
        val binding = binding()
        var clock = start
        val journal = RecordingNodeHandoffJournal(storage) { clock }.apply { recover() }
        journal.prepare(binding)
        journal.reserveOffer(binding)
        clock = binding.publishBefore
        assertFalse(journal.stillAllowed(binding))
        assertThrows(IllegalStateException::class.java) { journal.reserveOffer(binding) }
        clock = start
        storage.fail = true
        assertThrows(IllegalStateException::class.java) { journal.revokeRoom(binding.room) }
        assertFalse(journal.stillAllowed(binding))
        assertThrows(IllegalStateException::class.java) { journal.pending(binding.accountPubkey) }
    }

    @Test fun unofferedCancellationNeedsNoNodeAndCannotBecomeAQueueOffer() {
        val binding = binding()
        val journal = RecordingNodeHandoffJournal(Storage()) { start }.apply { recover() }
        journal.prepare(binding)
        journal.revokeRoom(binding.room)
        assertThrows(IllegalStateException::class.java) { journal.reserveOffer(binding) }
        journal.retire(binding)
        assertTrue(journal.pending(binding.accountPubkey).isEmpty())
    }
}
