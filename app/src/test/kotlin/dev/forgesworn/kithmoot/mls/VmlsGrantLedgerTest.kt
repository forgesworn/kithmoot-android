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
    private suspend fun VmlsGrantLedger.grant(at: Long, persona: String = keeper.pubkey) =
        plan(keeper, persona, box, device, at).also { record(VmlsGrantRecord(box, it)) }

    @Test fun `a first grant has a new id, thirty days, and its revocation one second later`() = runBlocking<Unit> {
        val ledger = VmlsGrantLedger(MemoryStorage())
        val plan = ledger.plan(keeper, keeper.pubkey, box, device, now)
        assertEquals(now, plan.active.createdAt)
        assertEquals(now + 1, plan.revoked.createdAt)
        assertEquals((now + VMLS_GRANT_LIFETIME_SECONDS).toString(), tag(plan, "expiration"))
        assertEquals(device, tag(plan, "device"))
        assertEquals(keeper.pubkey, tag(plan, "p"))
        assertNull(ledger.get(box, device), "a plan is not stored until recorded")
    }

    @Test fun `a renewal reuses the id, never lowers the expiration, and is later than the grant held`() = runBlocking<Unit> {
        val storage = MemoryStorage()
        val ledger = VmlsGrantLedger(storage)
        val first = ledger.grant(now)

        // The box's clock is behind the first grant: the renewal still sorts after it, by one second.
        val renewal = VmlsGrantLedger(storage).plan(keeper, keeper.pubkey, box, device, now - 50)
        assertEquals(tag(first, "grant"), tag(renewal, "grant"))
        assertEquals(tag(first, "expiration"), tag(renewal, "expiration"))
        assertEquals(first.active.createdAt + 1, renewal.active.createdAt)

        val later = ledger.plan(keeper, keeper.pubkey, box, device, now + 86_400)
        assertEquals((now + 86_400 + VMLS_GRANT_LIFETIME_SECONDS).toString(), tag(later, "expiration"))
        // The revocation carries the latest expiration, as rule 17 needs.
        assertEquals(tag(later, "expiration"), later.revoked.tags.single { it[0] == "expiration" }[1])
    }

    @Test fun `a stale plan, or another id while one is live, is never stored over the grant held`() = runBlocking<Unit> {
        val ledger = VmlsGrantLedger(MemoryStorage())
        val first = ledger.plan(keeper, keeper.pubkey, box, device, now)
        val second = ledger.plan(keeper, keeper.pubkey, box, device, now) // a concurrent first grant: another id
        ledger.record(VmlsGrantRecord(box, first))
        assertFailsWith<IllegalArgumentException> { ledger.record(VmlsGrantRecord(box, second)) }
        val older = ledger.plan(keeper, keeper.pubkey, box, device, now + 10)
        ledger.record(VmlsGrantRecord(box, ledger.plan(keeper, keeper.pubkey, box, device, now + 20)))
        assertFailsWith<IllegalArgumentException> { ledger.record(VmlsGrantRecord(box, older)) }
        assertEquals(now + 20, ledger.get(box, device)!!.plan.active.createdAt)
    }

    @Test fun `a revocation is handed out until the box confirms it, then the next grant takes a new id`() = runBlocking<Unit> {
        val storage = MemoryStorage()
        val ledger = VmlsGrantLedger(storage)
        val plan = ledger.grant(now)
        assertEquals(plan.revoked, VmlsGrantLedger(storage).revoke(box, device))
        // The publish failed: the same revocation again, and no renewal meanwhile.
        assertEquals(plan.revoked, ledger.revoke(box, device))
        assertEquals(VmlsGrantState.REVOKING, ledger.get(box, device)!!.state)
        assertFailsWith<IllegalArgumentException> { ledger.plan(keeper, keeper.pubkey, box, device, now + 5) }
        ledger.revoked(box, device, plan.revoked)
        assertNull(ledger.revoke(box, device))
        val next = ledger.grant(now + 10)
        assertNotEquals(tag(plan, "grant"), tag(next, "grant"))
        assertNull(ledger.revoke(box, "ef".repeat(32)))
    }

    @Test fun `a guest's grant names the guest, and its device stays the guest's`() = runBlocking<Unit> {
        val ledger = VmlsGrantLedger(MemoryStorage())
        val guest = "77".repeat(32)
        val plan = ledger.grant(now, persona = guest)
        assertEquals(guest, tag(plan, "p"))
        assertEquals(keeper.pubkey, plan.active.pubkey)
        assertEquals(guest, ledger.get(box, device)!!.persona)
        assertFailsWith<IllegalArgumentException> { ledger.plan(keeper, keeper.pubkey, box, device, now + 5) }
    }

    @Test fun `revoked and lapsed grants are pruned, and a lapsed one is replaced by a new id`() = runBlocking<Unit> {
        val ledger = VmlsGrantLedger(MemoryStorage())
        val plan = ledger.grant(now)
        val lapsed = now + VMLS_GRANT_LIFETIME_SECONDS + 1
        val fresh = ledger.plan(keeper, keeper.pubkey, box, device, lapsed)
        assertNotEquals(tag(plan, "grant"), tag(fresh, "grant"))
        ledger.record(VmlsGrantRecord(box, fresh))
        ledger.prune(lapsed, lapsed)
        assertEquals(1, ledger.all().size)
        ledger.prune(lapsed + VMLS_GRANT_LIFETIME_SECONDS, lapsed + VMLS_GRANT_LIFETIME_SECONDS)
        assertTrue(ledger.all().isEmpty())
    }

    @Test fun `a box clock far ahead keeps a live grant's id, and never lets a new id overwrite it`() = runBlocking<Unit> {
        val ledger = VmlsGrantLedger(MemoryStorage())
        val first = ledger.grant(now)
        val ahead = now + VMLS_GRANT_LIFETIME_SECONDS + 86_400
        // Lapsed by the box's clock alone: the phone's says it is live, so its id is kept.
        assertEquals(tag(first, "grant"), tag(ledger.plan(keeper, keeper.pubkey, box, device, ahead, now), "grant"))
        // A plan for a new id, made on the box's clock alone, cannot replace it while the phone says it is live.
        val stray = ledger.plan(keeper, keeper.pubkey, box, device, ahead)
        assertNotEquals(tag(first, "grant"), tag(stray, "grant"))
        assertFailsWith<IllegalArgumentException> { ledger.record(VmlsGrantRecord(box, stray), now) }
        assertEquals(tag(first, "grant"), ledger.get(box, device)!!.grantId)
        // Once the phone's clock agrees it lapsed, a new id is recorded.
        ledger.record(VmlsGrantRecord(box, stray), ahead)
        assertEquals(tag(stray, "grant"), ledger.get(box, device)!!.grantId)
    }

    @Test fun `a box clock far ahead does not erase a live grant's record`() = runBlocking<Unit> {
        val ledger = VmlsGrantLedger(MemoryStorage())
        ledger.grant(now)
        ledger.prune(now + VMLS_GRANT_LIFETIME_SECONDS + 86_400, now)
        assertEquals(1, ledger.all().size)
        // Nor does a phone clock far ahead, while the box's says the grant is live.
        ledger.prune(now, now + VMLS_GRANT_LIFETIME_SECONDS + 86_400)
        assertEquals(1, ledger.all().size)
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
