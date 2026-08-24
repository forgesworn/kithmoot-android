package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Digests
import java.util.Base64
import kotlin.math.floor

/** The default TURN username suffix when a viewer is not named individually. */
const val DEFAULT_TURN_NAME: String = "kithmoot"

/** A short-lived TURN credential, in coturn's REST form. */
data class TurnCredential(val username: String, val credential: String)

/**
 * Mints a TURN credential using coturn's REST convention: the username is
 * `<expiry>:<name>` and the password is the HMAC-SHA1 of that username under a
 * shared secret, base64-encoded.
 *
 * The point is that no per-user account exists on the TURN server at all - it
 * holds one secret, and anybody who can be handed a credential is authorised
 * until the timestamp in their own username runs out.
 */
fun mintTurnCredential(
    secret: String,
    ttlSeconds: Double,
    now: Double = System.currentTimeMillis() / 1000.0,
    name: String? = null,
): TurnCredential {
    // Floor each half before summing, so the expiry is always a whole second.
    val expiresAt = floor(now).toLong() + floor(ttlSeconds).toLong()
    val username = "$expiresAt:${name ?: DEFAULT_TURN_NAME}"
    val mac = Digests.hmacSha1(
        secret.toByteArray(Charsets.UTF_8),
        username.toByteArray(Charsets.UTF_8),
    )
    return TurnCredential(username = username, credential = Base64.getEncoder().encodeToString(mac))
}
