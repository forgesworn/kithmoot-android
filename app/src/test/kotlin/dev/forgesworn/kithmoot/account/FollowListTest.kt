package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.Events
import kotlin.test.*

class FollowListTest {
    private val secret = ByteArray(32) { 7 }
    private val other = ByteArray(32) { 9 }
    private val me = Schnorr.publicKeyHex(secret)
    private val now = 1_800_000_000L
    private fun key(n: Int) = "%02x".format(n).repeat(32)
    private fun list(at: Long, vararg p: String, by: ByteArray = secret) =
        Events.sign(by, 3, at, p.map { listOf("p", it) }, "")

    @Test fun `the newest list wins and keeps its order, each key once, never the account itself`() {
        val old = list(now - 100, key(1))
        val new = list(now - 10, key(3), key(2), key(3), me, "not-a-key", key(4).uppercase())
        assertEquals(listOf(key(3), key(2), key(4)), newestFollowList(listOf(old, new), me, now))
    }

    @Test fun `another author, a forged signature or a list from the future is ignored`() {
        val theirs = list(now, key(1), by = other).copy(pubkey = me)
        val future = list(now + 3600, key(2))
        assertNull(newestFollowList(listOf(theirs, future, list(now, key(5), by = other)), me, now))
    }

    @Test fun `no list at all is null, an empty list is empty`() {
        assertNull(newestFollowList(emptyList(), me, now))
        assertEquals(emptyList(), newestFollowList(listOf(list(now)), me, now))
    }

    @Test fun `names for waiting inviters are asked for first, otherwise in list order`() {
        assertEquals(listOf(key(3), key(1), key(2), key(4)), followNameOrder(listOf(key(1), key(2), key(3), key(4)), setOf(key(3), key(9))))
        assertEquals(listOf(key(1), key(2)), followNameOrder(listOf(key(1), key(2)), emptySet()))
    }
}
