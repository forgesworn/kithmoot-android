package dev.forgesworn.kithmoot.account

/**
 * The release stub: every call refuses with "engine not in this build". A
 * coordinated vault over it cannot open a persona, take a genesis or stage a
 * write, so it never writes unwitnessed.
 */
object NoVaultWitness : VaultWitness {
    override fun objectHash(sealed: ByteArray): ByteArray = throw VaultWitnessUnavailableException()
    override fun genesis(subject: ByteArray, installation: ByteArray, witnessKey: ByteArray, active: List<CoordEntry>): CoordGenesis =
        throw VaultWitnessUnavailableException()
    override fun open(state: ByteArray, active: List<CoordEntry>, staged: List<CoordEntry>?): WitnessCoordinator =
        throw VaultWitnessUnavailableException()
}
