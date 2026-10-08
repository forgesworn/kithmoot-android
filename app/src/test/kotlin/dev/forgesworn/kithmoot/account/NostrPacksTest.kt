package dev.forgesworn.kithmoot.account

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NostrPacksTest {
    @Test fun `member registry is consulted only after the account proves signer ownership`() = runTest {
        val signer = LocalSigner(ByteArray(32) { 1 })
        assertTrue(unlockCultPack(signer, { "{\"names\":{\"member\":\"${signer.pubkey}\"}}" }, 100))
        assertFalse(unlockCultPack(signer, { "{\"names\":{}}" }, 100))
        val other = LocalSigner(ByteArray(32) { 2 })
        var fetched = false
        val forged = object : ParticipantSigner by signer {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String) = other.sign(kind, createdAt, tags, content)
        }
        assertFailsWith<SignerException> { unlockCultPack(forged, { fetched = true; "{}" }, 100) }
        assertFalse(fetched)
    }
}
