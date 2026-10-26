package dev.forgesworn.kithmoot.account

/** Optional typed capability; never a generic RPC or plaintext child export. */
interface RendezvousProvisionSigner {
    suspend fun provisionRendezvous(
        targetDevice: String, index: Long, nonce: ByteArray, expiresAt: Long,
        current: () -> Boolean = { true },
    ): String
}
