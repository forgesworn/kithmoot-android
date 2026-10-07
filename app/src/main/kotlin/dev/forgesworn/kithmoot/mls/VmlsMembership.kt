package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex

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
     * The 32 bytes a removal names a grant by: the grant's scope at [box] and
     * its `grant` id (16 bytes), hashed. The id outlives a renewal, which
     * re-signs the event, so the event id would not do.
     */
    fun grantRef(box: String, grantId: String): String =
        Digests.sha256(REF_LABEL + box.hexToBytes() + grantId.hexToBytes()).toHex()

    /**
     * The grants a removal of [devices] from [persona]'s room at [box] lists
     * (P3-05 decision 5): each device's live grant there. A grant [persona]
     * issued is its to revoke; one another persona on this phone issued is
     * listed as not this keeper's. A grant another of the persona's rooms on
     * the box still holds is listed too: it stays live, and listing it keeps
     * the removal from claiming box access ended. The persona's own device,
     * whose grant a removal never revokes, is not listed.
     */
    fun grants(persona: String, box: String, devices: Collection<String>, ledger: List<VmlsGrantRecord>): List<RemovalGrant> =
        devices.distinct().mapNotNull { device ->
            ledger.singleOrNull { it.box == box && it.device == device }
                ?.takeIf { it.state != VmlsGrantState.REVOKED && it.persona != persona }
                ?.let { RemovalGrant(box, grantRef(box, it.grantId), device, keeper = it.issuer == persona) }
        }

    /**
     * Which of [listed] (box and grant reference) the ledger shows revoked.
     * A renewal keeps a grant's id, so the latest record speaks for it; one
     * pruned or lapsed is not shown revoked, and its removal claims less.
     */
    fun revoked(listed: Collection<Pair<String, String>>, ledger: List<VmlsGrantRecord>): Set<Pair<String, String>> =
        listed.filter { (box, ref) -> ledger.any { it.box == box && it.state == VmlsGrantState.REVOKED && grantRef(it.box, it.grantId) == ref } }.toSet()

    /**
     * The journal cache after one kept write of [removal] under [key]: the
     * removals and their compromised marks, or null when there is no whole
     * journal to add it to ([journal] or [marks] not read), so the next read
     * goes to the vault. A cache built from one write would read as the whole
     * journal and lift other rooms' holds. [compromised] null keeps the mark.
     */
    fun cached(
        journal: Map<String, ByteArray>?, marks: Set<String>?, key: String, removal: ByteArray, compromised: Boolean?,
    ): Pair<Map<String, ByteArray>, Set<String>>? {
        if (journal == null || marks == null) return null
        return (journal + (key to removal)) to when (compromised) { true -> marks + key; false -> marks - key; null -> marks }
    }

    /**
     * Whether [session]'s sends are held: a removal there is marked
     * compromised and its Remove is not [committed]. A mark whose removal is
     * missing holds too.
     */
    fun held(journal: Map<String, ByteArray>, marks: Set<String>, session: String, committed: (ByteArray) -> Boolean): Boolean =
        marks.any { session(it) == session && journal[it]?.let(committed) != true }

    private val REF_LABEL = "kithmoot/vmls-removal-grant/v1".toByteArray(Charsets.UTF_8)
}

/** A box grant a removal lists, by its [ref] ([VmlsMembership.grantRef]): [keeper] when this persona issued it. */
data class RemovalGrant(val box: String, val ref: String, val device: String, val keeper: Boolean)
