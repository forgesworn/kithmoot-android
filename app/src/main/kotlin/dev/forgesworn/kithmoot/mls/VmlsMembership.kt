package dev.forgesworn.kithmoot.mls

/**
 * The membership journal's Kotlin side (contract §7, vennel P3-05b): which
 * grants a removal lists, and which of them the ledger shows revoked. Every
 * rule about the removal itself is the engine's (`VmlsRemoval`); its record
 * stays opaque here and in the vault.
 */
object VmlsMembership {
    /** A removal's key in the persona's journal: its session and its target, a leaf or a person's identity. */
    fun key(session: String, target: String): String = "$session:$target"

    /** The session a journal [key] belongs to. */
    fun session(key: String): String = key.substringBefore(':')

    /**
     * The grants a removal of [devices] from [persona]'s room at [box] lists
     * (P3-05 decision 5): each device's grant there, if live. A grant
     * [persona] issued is its to revoke; one another persona on this phone
     * issued is listed as not this keeper's. A device another of the
     * persona's rooms on the box still holds ([placed]) keeps its grant, so
     * it is not listed and the removal claims nothing about it; nor is the
     * persona's own device, whose grant a removal never revokes.
     */
    fun grants(persona: String, box: String, devices: Collection<String>, ledger: List<VmlsGrantRecord>, placed: Set<String>): List<RemovalGrant> =
        devices.distinct().filterNot { it in placed }.mapNotNull { device ->
            ledger.singleOrNull { it.box == box && it.device == device }
                ?.takeIf { it.state != VmlsGrantState.REVOKED && it.persona != persona }
                ?.let { RemovalGrant(box, it.grantId, device, keeper = it.issuer == persona) }
        }

    /**
     * Which of [listed] (box and grant id) the ledger shows revoked. A
     * renewal keeps a grant's id, so the latest record speaks for it; one
     * pruned or lapsed is not shown revoked, and its removal claims less.
     */
    fun revoked(listed: Collection<Pair<String, String>>, ledger: List<VmlsGrantRecord>): Set<Pair<String, String>> =
        listed.filter { (box, grant) -> ledger.any { it.box == box && it.grantId == grant && it.state == VmlsGrantState.REVOKED } }.toSet()
}

/** A box grant a removal lists: [keeper] when this persona issued it, and so may revoke it. */
data class RemovalGrant(val box: String, val grantId: String, val device: String, val keeper: Boolean)
