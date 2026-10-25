package dev.forgesworn.kithmoot.mls

/** Product boundary only: the experimental wire keeps independent homes. */
internal object InvitationDevelopmentPolicy {
    fun requireEnabled(debug: Boolean, explicitlyEnabled: Boolean) {
        check(debug && explicitlyEnabled) { "Experimental invitation MLS requires an explicitly enabled development build." }
    }
    fun requireSingleHome(groupHome: ByteArray, leafHome: ByteArray) {
        require(groupHome.size == 32 && leafHome.size == 32 && groupHome.contentEquals(leafHome)) {
            "This development app currently delivers through one paired home. The invitation names different group and leaf homes."
        }
    }
}
