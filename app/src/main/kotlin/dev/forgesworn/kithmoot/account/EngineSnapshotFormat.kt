package dev.forgesworn.kithmoot.account

/** Dispatch only. Native readers validate all snapshot bytes and the host
 * separately checks the sealed persistence generation and high-water witness. */
internal enum class EngineSnapshotFormat {
    Legacy, Invitation1, Admin2;

    companion object {
        /** Runtime restore dispatch checks policy before selecting any native opener. */
        fun readAuthorized(plaintext: ByteArray, policy: dev.forgesworn.kithmoot.mls.InvitationAccessPolicy): EngineSnapshotFormat =
            read(plaintext).also(policy::requireRestore)

        private val magic = "VMLS/1 snapshot".toByteArray(Charsets.US_ASCII)
        fun read(plaintext: ByteArray): EngineSnapshotFormat {
            require(plaintext.size > magic.size && plaintext.copyOfRange(0, magic.size).contentEquals(magic)) { "Unknown MLS snapshot format." }
            return when (val version = plaintext[magic.size].toInt() and 255) {
                1, 2 -> Legacy
                3, 4 -> Invitation1
                5 -> Admin2
                else -> error("Unknown MLS snapshot version: $version")
            }
        }
    }
}
