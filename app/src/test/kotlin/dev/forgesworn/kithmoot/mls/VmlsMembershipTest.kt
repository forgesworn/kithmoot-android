package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.crypto.Digests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

    @Test fun `a grant is named by 32 bytes over its box and id, the same across a renewal`() = runBlocking<Unit> {
        val first = granted(keeper, guest, phone)
        val ref = VmlsMembership.grantRef(box, first.grantId)
        assertEquals(64, ref.length)
        assertEquals(ref, VmlsMembership.grantRef(box, first.grantId))
        assertNotEquals(ref, VmlsMembership.grantRef(elsewhere, first.grantId))
    }

    @Test fun `the keeper's grants are listed as its to revoke, another persona's as not, and the rest not at all`() = runBlocking<Unit> {
        val mine = granted(keeper, guest, phone)
        val theirs = granted(other, guest, tablet)
        val own = granted(keeper, keeper.pubkey, "78".repeat(32))
        val atAnotherBox = granted(keeper, guest, "9a".repeat(32), at = elsewhere)
        val ledger = listOf(mine, theirs, own, atAnotherBox)
        val listed = VmlsMembership.grants(keeper.pubkey, box, listOf(phone, tablet, own.device, atAnotherBox.device, "bc".repeat(32), phone), ledger)
        assertEquals(
            listOf(
                RemovalGrant(box, VmlsMembership.grantRef(box, mine.grantId), phone, keeper = true),
                RemovalGrant(box, VmlsMembership.grantRef(box, theirs.grantId), tablet, keeper = false),
            ),
            listed,
            "each once; the persona's own device, a grant at another box and a device without one are not listed",
        )
    }

    @Test fun `a revoked grant is not listed again, and only a revoked record with the same id shows revoked`() = runBlocking<Unit> {
        val live = granted(keeper, guest, phone)
        val revoked = live.copy(state = VmlsGrantState.REVOKED)
        assertEquals(emptyList(), VmlsMembership.grants(keeper.pubkey, box, listOf(phone), listOf(revoked)))
        val listed = listOf(box to VmlsMembership.grantRef(box, live.grantId))
        assertEquals(emptySet(), VmlsMembership.revoked(listed, listOf(live)))
        assertEquals(emptySet(), VmlsMembership.revoked(listed, listOf(live.copy(state = VmlsGrantState.REVOKING))))
        assertEquals(listed.toSet(), VmlsMembership.revoked(listed, listOf(revoked)))
        // Another id at the same box and device (a new grant after a revocation) says nothing of the one listed.
        val next = granted(keeper, guest, phone).copy(state = VmlsGrantState.REVOKED)
        assertEquals(emptySet(), VmlsMembership.revoked(listed, listOf(next)))
        // Pruned from the ledger: not shown revoked, so the removal claims less, never more.
        assertEquals(emptySet(), VmlsMembership.revoked(listed, emptyList()))
    }

    @Test fun `one write is added only to a whole journal, never taken for it`() {
        val a = "a".repeat(64); val b = "b".repeat(64); val leaf = "c".repeat(64)
        // Evicted or refused before: no cache, so the next read goes to the vault and still sees room b's mark.
        assertNull(VmlsMembership.cached(null, null, "$a:$leaf", byteArrayOf(1), compromised = false))
        assertNull(VmlsMembership.cached(mapOf("$b:$leaf" to byteArrayOf(2)), null, "$a:$leaf", byteArrayOf(1), null))
        val (journal, marks) = VmlsMembership.cached(mapOf("$b:$leaf" to byteArrayOf(2)), setOf("$b:$leaf"), "$a:$leaf", byteArrayOf(1), null)!!
        assertEquals(setOf("$a:$leaf", "$b:$leaf"), journal.keys)
        assertEquals(setOf("$b:$leaf"), marks)
        assertEquals(setOf("$a:$leaf", "$b:$leaf"), VmlsMembership.cached(journal, marks, "$a:$leaf", byteArrayOf(3), true)!!.second)
        assertEquals(emptySet(), VmlsMembership.cached(journal, marks, "$b:$leaf", byteArrayOf(3), false)!!.second)
    }

    @Test fun `a room is held while a compromised removal there is not committed, or its record is missing`() {
        val a = "a".repeat(64); val b = "b".repeat(64); val leaf = "c".repeat(64)
        val committed = { bytes: ByteArray -> bytes[0] == 1.toByte() }
        val journal = mapOf("$a:$leaf" to byteArrayOf(0), "$b:$leaf" to byteArrayOf(1))
        assertTrue(VmlsMembership.held(journal, setOf("$a:$leaf"), a, committed))
        assertFalse(VmlsMembership.held(journal, setOf("$a:$leaf"), b, committed), "another room's mark holds only its own room")
        assertFalse(VmlsMembership.held(journal, setOf("$b:$leaf"), b, committed), "released once committed")
        assertFalse(VmlsMembership.held(journal, emptySet(), a, committed), "an ordinary removal holds nothing")
        assertTrue(VmlsMembership.held(emptyMap(), setOf("$a:$leaf"), a, committed), "a mark without its record holds")
    }
}
