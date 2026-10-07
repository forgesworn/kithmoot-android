package dev.forgesworn.kithmoot.account

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The debug build's hook for sign-out and account switch (§6.2): it moves the one vault's durable epoch. */
class VaultSessionEndTest {
    @Test fun `ending the session writes a new epoch and makes an earlier context stale`() {
        val stores = MemoryCoordinatedStores()
        val server = FakeWitnessServer(ByteArray(32).also(SecureRandom()::nextBytes))
        val vault = MlsVault.coordinated(VaultCoordination(stores, WitnessChannels { _, _, _ -> server.channel }, FakeVaultWitness()))
        val earlier = vault.context("dev.forgesworn.kithmoot", "aa".repeat(32))
        val before = assertNotNull(stores.sealed(MlsVault.EPOCH))
        vaultSessionEnd { vault }.end()
        val after = assertNotNull(stores.sealed(MlsVault.EPOCH))
        assertFalse(before.value.contentEquals(after.value))
        assertFalse(vault.isCurrent(earlier))
        assertTrue(vault.isCurrent(vault.context("dev.forgesworn.kithmoot", "aa".repeat(32))))
    }

    @Test fun `a new session is not the old one's`() {
        val stores = MemoryCoordinatedStores()
        val server = FakeWitnessServer(ByteArray(32).also(SecureRandom()::nextBytes))
        val vault = MlsVault.coordinated(VaultCoordination(stores, WitnessChannels { _, _, _ -> server.channel }, FakeVaultWitness()))
        val end = vaultSessionEnd { vault }
        val a = vault.context("p", "aa".repeat(32))
        end.end()
        val b = vault.context("p", "aa".repeat(32))
        end.end()
        assertEquals(listOf(false, false), listOf(vault.isCurrent(a), vault.isCurrent(b)))
    }
}
