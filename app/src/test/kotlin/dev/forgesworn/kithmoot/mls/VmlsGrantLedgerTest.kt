package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.protocol.CircleGrantStatus
import dev.forgesworn.kithmoot.protocol.CircleGrantTerms
import dev.forgesworn.kithmoot.protocol.KIND_CIRCLE_EVENT_GRANT
import dev.forgesworn.kithmoot.protocol.VMLS_GRANT_LIFETIME_SECONDS
import dev.forgesworn.kithmoot.relay.CircleGrantPlan
import dev.forgesworn.kithmoot.storage.MemoryStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** The keeper's VMLS grant ledger against Bothy's renewal and revocation rules (P3-03b-3b-2). */
class VmlsGrantLedgerTest {
    private val keeper = LocalSigner(Digests.sha256("vmls-ledger-test".toByteArray()))
    private val box = "ab".repeat(32)
    private val device = "cd".repeat(32)
    private val now = 1_900_000_000L

    private fun tag(plan: CircleGrantPlan, name: String) = plan.active.tags.single { it[0] == name }[1]

    @Test fun `a first grant has a new id, thirty days, and its revocation one second later`() = runBlocking<Unit> {
        val ledger = VmlsGrantLedger(MemoryStorage())
        val plan = ledger.plan(keeper, keeper.pubkey, box, device, now)
        assertEquals(now, plan.active.createdAt)
        assertEquals(now + 1, plan.revoked.createdAt)
        assertEquals((now + VMLS_GRANT_LIFETIME_SECONDS).toString(), tag(plan, "expiration"))
        assertEquals(device, tag(plan, "device"))
        assertEquals(keeper.pubkey, tag(plan, "p"))
        assertNull(ledger.get(keeper.pubkey, box, device), "a plan is not stored until recorded")
    }

    @Test fun `a renewal reuses the id, never lowers the expiration, and is later than the retained revocation`() = runBlocking<Unit> {
        val storage = MemoryStorage()
        val ledger = VmlsGrantLedger(storage)
        val first = ledger.plan(keeper, keeper.pubkey, box, device, now)
        ledger.record(VmlsGrantRecord(box, first))

        // The box's clock is behind the first grant: the renewal still sorts after it.
        val renewal = VmlsGrantLedger(storage).plan(keeper, keeper.pubkey, box, device, now - 50)
        assertEquals(tag(first, "grant"), tag(renewal, "grant"))
        assertEquals(tag(first, "expiration"), tag(renewal, "expiration"))
        assertTrue(renewal.active.createdAt > first.revoked.createdAt)
        assertEquals(renewal.active.createdAt + 1, renewal.revoked.createdAt)

        val later = ledger.plan(keeper, keeper.pubkey, box, device, now + 86_400)
        assertEquals((now + 86_400 + VMLS_GRANT_LIFETIME_SECONDS).toString(), tag(later, "expiration"))
        // The revocation carries the latest expiration, as rule 17 needs.
        assertEquals(tag(later, "expiration"), later.revoked.tags.single { it[0] == "expiration" }[1])
    }

    @Test fun `the retained revocation is handed out once, and the next grant takes a new id`() = runBlocking<Unit> {
        val storage = MemoryStorage()
        val ledger = VmlsGrantLedger(storage)
        val plan = ledger.plan(keeper, keeper.pubkey, box, device, now)
        ledger.record(VmlsGrantRecord(box, plan))
        assertEquals(plan.revoked, VmlsGrantLedger(storage).revoke(keeper.pubkey, box, device))
        assertNull(ledger.revoke(keeper.pubkey, box, device))
        assertTrue(VmlsGrantLedger(storage).get(keeper.pubkey, box, device)!!.revoked)
        assertNotEquals(tag(plan, "grant"), tag(ledger.plan(keeper, keeper.pubkey, box, device, now + 10), "grant"))
        assertNull(ledger.revoke(keeper.pubkey, box, "ef".repeat(32)))
    }

    @Test fun `a guest's grant names the guest, and its device stays the guest's`() = runBlocking<Unit> {
        val ledger = VmlsGrantLedger(MemoryStorage())
        val guest = "77".repeat(32)
        val plan = ledger.plan(keeper, guest, box, device, now)
        assertEquals(guest, tag(plan, "p"))
        assertEquals(keeper.pubkey, plan.active.pubkey)
        ledger.record(VmlsGrantRecord(box, plan))
        assertEquals(guest, ledger.get(keeper.pubkey, box, device)!!.persona)
        assertFailsWith<IllegalArgumentException> { ledger.plan(keeper, keeper.pubkey, box, device, now + 5) }
    }

    @Test fun `grants are kept per issuer, box and device`() = runBlocking<Unit> {
        val ledger = VmlsGrantLedger(MemoryStorage())
        val other = "12".repeat(32)
        ledger.record(VmlsGrantRecord(box, ledger.plan(keeper, keeper.pubkey, box, device, now)))
        ledger.record(VmlsGrantRecord(other, ledger.plan(keeper, keeper.pubkey, other, device, now)))
        ledger.record(VmlsGrantRecord(box, ledger.plan(keeper, keeper.pubkey, box, device, now + 5)))
        assertEquals(2, ledger.all().size)
        assertNull(ledger.get("99".repeat(32), box, device))
    }

    @Test fun `a record refuses a grant for another box, or a room grant`() = runBlocking<Unit> {
        val ledger = VmlsGrantLedger(MemoryStorage())
        val plan = ledger.plan(keeper, keeper.pubkey, box, device, now)
        assertFailsWith<IllegalArgumentException> { VmlsGrantRecord("12".repeat(32), plan) }
        val room = CircleGrantTerms(tag(plan, "server"), "42".repeat(32), keeper.pubkey, device, "33".repeat(16), now + 100)
        val roomPlan = CircleGrantPlan(
            keeper.sign(KIND_CIRCLE_EVENT_GRANT, now, room.tags(CircleGrantStatus.ACTIVE), ""),
            keeper.sign(KIND_CIRCLE_EVENT_GRANT, now + 1, room.tags(CircleGrantStatus.REVOKED), ""),
        )
        assertFailsWith<IllegalArgumentException> { VmlsGrantRecord(box, roomPlan) }
    }
}
