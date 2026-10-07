package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.crypto.Digests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/** Which grants a removal lists, and which the ledger shows revoked (P3-05b). */
class VmlsMembershipTest {
    private val keeper = LocalSigner(Digests.sha256("vmls-membership-keeper".toByteArray()))
    private val other = LocalSigner(Digests.sha256("vmls-membership-other".toByteArray()))
    private val box = "ab".repeat(32)
    private val elsewhere = "ef".repeat(32)
    private val guest = "12".repeat(32)
    private val phone = "34".repeat(32)
    private val tablet = "56".repeat(32)
    private val now = 1_900_000_000L

    private suspend fun granted(issuer: LocalSigner, persona: String, device: String, at: String = box) =
        VmlsGrantRecord(at, VmlsGrantLedger(dev.forgesworn.kithmoot.storage.MemoryStorage()).plan(issuer, persona, at, device, now))

    @Test fun `a key names its session and target, and a session is read back from it`() {
        val session = "aa".repeat(32)
        val key = VmlsMembership.key(session, phone)
        assertEquals("$session:$phone", key)
        assertEquals(session, VmlsMembership.session(key))
    }

    @Test fun `the keeper's grants are listed as its to revoke, another persona's as not, and the rest not at all`() = runBlocking<Unit> {
        val mine = granted(keeper, guest, phone)
        val theirs = granted(other, guest, tablet)
        val own = granted(keeper, keeper.pubkey, "78".repeat(32))
        val atAnotherBox = granted(keeper, guest, "9a".repeat(32), at = elsewhere)
        val ledger = listOf(mine, theirs, own, atAnotherBox)
        val listed = VmlsMembership.grants(keeper.pubkey, box, listOf(phone, tablet, own.device, atAnotherBox.device, "bc".repeat(32)), ledger, placed = emptySet())
        assertEquals(
            listOf(RemovalGrant(box, mine.grantId, phone, keeper = true), RemovalGrant(box, theirs.grantId, tablet, keeper = false)),
            listed,
            "the persona's own device, a grant at another box and a device without one are not listed",
        )
    }

    @Test fun `a device another room on the box holds keeps its grant and is not listed`() = runBlocking<Unit> {
        val ledger = listOf(granted(keeper, guest, phone), granted(keeper, guest, tablet))
        val listed = VmlsMembership.grants(keeper.pubkey, box, listOf(phone, tablet, phone), ledger, placed = setOf(tablet))
        assertEquals(listOf(phone), listed.map { it.device }, "listed once, and the placed device not at all")
    }

    @Test fun `a revoked grant is not listed again, and only a revoked record with the same id shows revoked`() = runBlocking<Unit> {
        val live = granted(keeper, guest, phone)
        val revoked = live.copy(state = VmlsGrantState.REVOKED)
        assertEquals(emptyList(), VmlsMembership.grants(keeper.pubkey, box, listOf(phone), listOf(revoked), emptySet()))
        val listed = listOf(box to live.grantId)
        assertEquals(emptySet(), VmlsMembership.revoked(listed, listOf(live)))
        assertEquals(emptySet(), VmlsMembership.revoked(listed, listOf(live.copy(state = VmlsGrantState.REVOKING))))
        assertEquals(setOf(box to live.grantId), VmlsMembership.revoked(listed, listOf(revoked)))
        // Another id at the same box and device (a new grant after a revocation) says nothing of the one listed.
        val next = granted(keeper, guest, phone).copy(state = VmlsGrantState.REVOKED)
        assertEquals(emptySet(), VmlsMembership.revoked(listed, listOf(next)))
        // Pruned from the ledger: not shown revoked, so the removal claims less, never more.
        assertEquals(emptySet(), VmlsMembership.revoked(listed, emptyList()))
    }
}
