package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Schnorr
import kotlin.test.*

class ProvisionTransportKeyTest {
    @Test fun `Cambium target is app owned retained across protected record reopen and never persona key`() {
        val identity = "ab".repeat(32)
        val old = NostrAccount(identity, "nip55", signerPackage = "dev.forgesworn.cambium")
        val migrated = old.withProvisionTransportKey()
        assertNull(old.clientSecretKey)
        assertNotNull(migrated.clientSecretKey)
        assertNull(migrated.secretKey)
        assertNotEquals(identity, Schnorr.publicKeyHex(migrated.clientSecretKey!!))
        val reopened = NostrAccount.fromJson(migrated.toJson())
        assertContentEquals(migrated.clientSecretKey, reopened.clientSecretKey)
        assertSame(reopened, reopened.withProvisionTransportKey())
        assertNotEquals(Schnorr.publicKeyHex(migrated.clientSecretKey!!), Schnorr.publicKeyHex(old.withProvisionTransportKey().clientSecretKey!!))
    }
    @Test fun `other signers and existing bunker targets are never replaced`() {
        val other = NostrAccount("ab".repeat(32), "nip55", signerPackage = "example.other.signer")
        assertSame(other, other.withProvisionTransportKey())
        val bunker = NostrAccount("ab".repeat(32), "bunker", bunkerUri = "bunker://synthetic", clientSecretKey = ByteArray(32) { 7 })
        assertSame(bunker, bunker.withProvisionTransportKey())
    }
}
