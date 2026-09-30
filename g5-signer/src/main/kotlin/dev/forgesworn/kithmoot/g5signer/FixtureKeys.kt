package dev.forgesworn.kithmoot.g5signer

import android.content.Context
import dev.forgesworn.kithmoot.crypto.Schnorr
import java.security.MessageDigest

/** The configured persona's deterministic fixture key; never a real identity. */
internal object FixtureKeys {
    const val PREFERENCES = "g5-fixture-signer"
    const val PERSONA = "persona"

    fun key(context: Context): ByteArray? {
        val persona = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(PERSONA, null) ?: return null
        return MessageDigest.getInstance("SHA-256").digest("kithmoot-g5-fixture-signer-v2:$persona".toByteArray(Charsets.UTF_8))
    }

    fun publicKey(key: ByteArray): String = Schnorr.publicKeyHex(key)
}
