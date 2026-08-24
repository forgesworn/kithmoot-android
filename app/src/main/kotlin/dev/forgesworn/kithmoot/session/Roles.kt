package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.normaliseHex
import dev.forgesworn.kithmoot.protocol.RosterEntry

/** The role names that travel on the wire in a roster entry's `claims` map. */
object Roles {
    /** The one device of yours whose microphone is live. */
    const val MIC: String = "mic"

    /** The one device of yours that plays the room's audio. */
    const val MONITOR: String = "monitor"

    /** A camera track. Several of your devices may publish one at once. */
    const val CAMERA: String = "camera"

    /** A screen share. Several of your devices may publish one at once. */
    const val SCREEN: String = "screen"

    /**
     * The roles that at most one of a person's devices may hold.
     *
     * Both are physical facts rather than policy. Two live microphones in the
     * same room is a feedback loop; two audio sinks means hearing every other
     * participant twice, slightly out of step.
     */
    val SINGULAR: Set<String> = setOf(MIC, MONITOR)
}

/**
 * Decides which of a participant's devices holds a singular role.
 *
 * Every client in the room runs this over the same roster and must reach the
 * same answer without conferring, so it is a pure function of the claims and
 * nothing else - no local preference, no arrival order, no clock of our own.
 */
object RoleArbiter {

    /**
     * The device pubkey holding [role], or null if none of these devices claim
     * it.
     *
     * The most recent claim wins: picking up your phone should move the
     * microphone to your phone. Ties go to the **lowest device pubkey**, which
     * is arbitrary but total, so two devices that claim in the same second do
     * not each conclude they won.
     */
    fun holder(entries: Collection<RosterEntry>, role: String): String? = entries
        .filter { it.claims.containsKey(role) }
        .maxWithOrNull(
            // The tiebreak is lexicographic, not equality, so `hexEquals`
            // cannot help here: normalise both sides of the `<` at the one
            // place a device pubkey enters this comparison, the same way
            // `PeerLink`'s glare tiebreak does - see `normaliseHex`.
            compareBy<RosterEntry> { it.claims.getValue(role) }
                .thenByDescending { it.device.normaliseHex() },
        )
        ?.device
}
