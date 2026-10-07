package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.storage.MemoryStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Which credentials and grants the foreground rounds renew (P3-03b-3d). */
class VmlsRenewalTest {
    private val keeper = LocalSigner(Digests.sha256("vmls-renewal-test".toByteArray()))
    private val other = LocalSigner(Digests.sha256("vmls-renewal-other".toByteArray()))
    private val box = "ab".repeat(32)
    private val own = "cd".repeat(32)
    private val guest = "ef".repeat(32)
    private val gone = "12".repeat(32)
    private val now = 1_900_000_000L
    private val window = 7L * 86_400

    @Test fun `a credential is due at seven days left or less, and not before`() {
        assertFalse(VmlsRenewal.credentialDue(now + window + 1, now))
        assertTrue(VmlsRenewal.credentialDue(now + window, now))
        assertTrue(VmlsRenewal.credentialDue(now + 3600, now))
        assertTrue(VmlsRenewal.credentialDue(now - 1, now))
    }

    /** A grant stored at [at], which the box then dates by its own clock: it lasts thirty days. */
    private suspend fun VmlsGrantLedger.stored(device: String, at: Long, signer: LocalSigner = keeper) =
        plan(signer, if (signer === keeper) signer.pubkey else device, box, device, at).also { record(VmlsGrantRecord(box, it)) }

    @Test fun `only devices in use, with a grant expiring within seven days, are due`() = runBlocking<Unit> {
        val ledger = VmlsGrantLedger(MemoryStorage())
        // Granted 25 days ago: five days left. The other is fresh, the third is no longer in use.
        ledger.stored(own, now - 25 * 86_400)
        ledger.stored(guest, now)
        ledger.stored(gone, now - 25 * 86_400)
        val due = VmlsRenewal.grantsDue(ledger.all(), keeper.pubkey, box, setOf(own, guest), now, window)
        assertEquals(listOf(own), due.map { it.device })
    }

    @Test fun `a grant already lapsed but still stored is renewed while the device is in use`() = runBlocking<Unit> {
        val ledger = VmlsGrantLedger(MemoryStorage())
        ledger.stored(guest, now - 40 * 86_400)
        assertEquals(listOf(guest), VmlsRenewal.grantsDue(ledger.all(), keeper.pubkey, box, setOf(guest), now, window).map { it.device })
    }

    @Test fun `revoking, revoked, removed and another keeper's grants are not renewed`() = runBlocking<Unit> {
        val ledger = VmlsGrantLedger(MemoryStorage())
        val old = now - 25 * 86_400
        ledger.stored(own, old)
        ledger.stored(guest, old)
        ledger.stored(gone, old)
        val foreign = "34".repeat(32)
        ledger.stored(foreign, old, other)
        ledger.revoke(box, own)
        ledger.removed(box, guest, now - 3600)
        ledger.revoke(box, gone)
        ledger.revoked(box, gone, ledger.get(box, gone)!!.plan.revoked)
        val inUse = setOf(own, guest, gone, foreign)
        assertTrue(VmlsRenewal.grantsDue(ledger.all(), keeper.pubkey, box, inUse, now, window).isEmpty())
        // Kept in a room again, the removed device is renewed.
        ledger.kept(box, guest)
        assertEquals(listOf(guest), VmlsRenewal.grantsDue(ledger.all(), keeper.pubkey, box, inUse, now, window).map { it.device })
    }

    @Test fun `grants at another box are left`() = runBlocking<Unit> {
        val ledger = VmlsGrantLedger(MemoryStorage())
        ledger.stored(own, now - 25 * 86_400)
        assertTrue(VmlsRenewal.grantsDue(ledger.all(), keeper.pubkey, "99".repeat(32), setOf(own), now, window).isEmpty())
    }

    @Test fun `a renewal the box has not taken is kept marked across a restart, and published again until confirmed`() = runBlocking<Unit> {
        val storage = MemoryStorage()
        val ledger = VmlsGrantLedger(storage)
        ledger.stored(guest, now - 25 * 86_400)
        // Renewed and stored, then the publish failed and the app restarted.
        val renewal = ledger.plan(keeper, keeper.pubkey, box, guest, now, now)
        ledger.record(VmlsGrantRecord(box, renewal, unconfirmed = true), now)
        val restarted = VmlsGrantLedger(storage)
        assertEquals(listOf(guest), VmlsRenewal.unconfirmed(restarted.all(), keeper.pubkey, box, setOf(guest)).map { it.device })
        // Not in use any more: left alone, to lapse.
        assertTrue(VmlsRenewal.unconfirmed(restarted.all(), keeper.pubkey, box, emptySet()).isEmpty())
        // The box took it.
        restarted.confirmed(box, guest)
        assertTrue(VmlsRenewal.unconfirmed(VmlsGrantLedger(storage).all(), keeper.pubkey, box, setOf(guest)).isEmpty())
        assertFalse(VmlsGrantLedger(storage).get(box, guest)!!.unconfirmed)
    }
}
