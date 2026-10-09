package dev.forgesworn.kithmoot.account

/** A cached device credential may speak for an account offline. It is not
 * permission to contact its signer or mint additional account authority. */
internal class OfflineParticipantSigner(override val pubkey: String) : ParticipantSigner {
    override val method = "offline"
    override val canEncrypt = false
    private fun unavailable(): Nothing = throw SignerException("This action needs your account signer. Reopen the room using Internet first.")
    override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): Nothing = unavailable()
    override suspend fun nip44Encrypt(peer: String, plaintext: String): Nothing = unavailable()
    override suspend fun nip44Decrypt(peer: String, payload: String): Nothing = unavailable()
}
